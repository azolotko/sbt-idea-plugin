package org.jetbrains.sbt
package tasks

import java.net.URI
import java.nio.file.FileSystems.newFileSystem
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util

import org.jetbrains.sbtidea.Keys.PackagingMethod
import org.jetbrains.sbtidea.Keys.PackagingMethod.{MergeIntoOther, MergeIntoParent, Skip, Standalone}
import sbt.Def.Classpath
import sbt.Keys.{TaskStreams, moduleID}
import sbt.jetbrains.apiAdapter._
import sbt.{File, ModuleID, ProjectRef, UpdateReport, _}

import scala.collection.mutable

object PluginPackager {

  def artifactMappings(rootProject: ProjectRef,
                       outputDir: File,
                       projectsData: Seq[ProjectData],
                       buildDependencies: BuildDependencies,
                       streams: TaskStreams): Seq[(File, File)] = {

    def mkProjectData(projectData: ProjectData): ProjectData = {
      if (projectData.thisProject == rootProject && !projectData.packageMethod.isInstanceOf[Standalone]) {
        projectData.copy(packageMethod = Standalone())
      } else projectData
    }

    val projectMap    = projectsData.iterator.map(x => x.thisProject -> mkProjectData(x)).toMap
    val revProjectMap = projectsData.flatMap(x => buildDependencies.classpathRefs(x.thisProject).map(_ -> x.thisProject))

    def findProjectRef(project: Project): Option[ProjectRef] = projectMap.find(_._1.project == project.id).map(_._1)

    def walk(ref: ProjectRef, queue: Seq[ProjectRef]): Seq[ProjectRef] = {
      val data = projectMap(ref)
      if (!queue.contains(ref)) {
        val newQueue = queue :+ ref
        val direct = buildDependencies.classpathRefs(ref).foldLeft(newQueue) { case (q, r) => walk(r, q) }
        val additional = data.additionalProjects.flatMap(findProjectRef).foldLeft(direct) { case (q, r) => walk(r, q) }
        additional
      } else { queue }
    }

    def buildStructure(ref: ProjectRef): Seq[(File, File)] = {
      val artifactMap = new mutable.TreeSet[(File, File)]()

      def findParentToMerge(ref: ProjectRef): ProjectRef = projectMap.getOrElse(ref,
        throw new RuntimeException(s"Project $ref has no parent to merge into")) match {
        case ProjectData(p, _, _, _, _, _, _, _, _, _: Standalone) => p
        case _ => findParentToMerge(revProjectMap.filter(_._1 == ref).head._2)
      }

      val ProjectData(_,
                      cp,
                      definedDeps,
                      _,
                      assembleLibraries,
                      productDirs,
                      report,
                      libMapping,
                      additionalMappings,
                      method) = projectMap(ref)

      implicit val scalaVersion: ProjectScalaVersion = ProjectScalaVersion(definedDeps.find(_.name == "scala-library"))

      val resolver              = new TransitiveDeps(report, "compile")
      val mappings              = libMapping.map(x => x._1.key -> x._2).toMap
      val resolvedLibsNoEvicted = buildModuleIdMap(cp)
      val resolvedLibs          = updateWithEvictionMappings(resolvedLibsNoEvicted, resolver.evicted)
      val transitiveDeps        = definedDeps
        .filter(_.configurations.isEmpty)
        .map(_.key)
        .flatMap(resolver.collectTransitiveDeps)
      val processedLibs = transitiveDeps.map(m => m -> resolvedLibs.get(m))
        .map {
          case x@(mod, None) => streams.log.warn(s"couldn't resolve dependency jar: $mod"); x
          case other => other
        }.collect {
        case (mod, Some(file)) if !mappings.contains(mod)                 => file -> outputDir / mkRelativeLibPath(file)
        case (mod, Some(file)) if mappings.getOrElse(mod, None).isDefined => file -> outputDir / mappings(mod).get
      }

      val targetJar = method match {
        case Skip() => None
        case MergeIntoParent() =>
          val parent = findParentToMerge(ref)
          val parentFile = mkProjectJarPath(parent)
          productDirs.foreach { artifactMap += _ -> outputDir / parentFile }
          Some(outputDir / parentFile)
        case MergeIntoOther(project) =>
          val parent = findParentToMerge(findProjectRef(project)
            .getOrElse(throw new RuntimeException(s"Couldn't resolve project $project")))
          val otherFile = mkProjectJarPath(parent)
          productDirs.map { artifactMap += _ -> outputDir/ otherFile }
          Some(outputDir/ otherFile)
        case Standalone("") =>
          val file = outputDir / mkProjectJarPath(ref)
          productDirs.foreach { artifactMap += _ -> file }
          Some(file)
        case Standalone(targetPath) =>
          val file = outputDir / targetPath
          productDirs.foreach { artifactMap += _ -> file }
          Some(file)
      }

      targetJar match {
        case Some(file) if assembleLibraries =>
          artifactMap ++= processedLibs.map { case (in, _) => in -> file }
        case _ =>
          artifactMap ++= processedLibs
      }

      artifactMap ++= additionalMappings.map { case (from, to) => from -> outputDir / to }

      artifactMap.toSeq
    }

    val queue       = walk(rootProject, Seq.empty)
    val structures  = queue.reverse.map(buildStructure)
    val result      = new mutable.TreeSet[(File, File)]()
    structures.foreach(result ++= _)

    result.toSeq
  }

  private def buildModuleIdMap(cp: Classpath)(implicit scalaVersion: ProjectScalaVersion): Map[ModuleKey, File] = (for {
    jarFile <- cp
    moduleId <- jarFile.get(moduleID.key)
  } yield { moduleId.key -> jarFile.data }).toMap

  private def updateWithEvictionMappings(cpNoEvicted: Map[ModuleKey, File], evicted: Seq[ModuleKey]): Map[ModuleKey, File] = {
    val eviictionSubstitutes = evicted
      .map(ev => ev -> cpNoEvicted.find(entry => entry._1 ~== ev).map(_._2)
        .getOrElse(throw new RuntimeException(s"Can't resolve eviction for $ev")))
    cpNoEvicted ++ eviictionSubstitutes
  }

  def packageArtifact(structure: Seq[(File, File)], streams: TaskStreams): Unit = structure.foreach { entry =>
    val (from, to) = entry
    if (!to.exists() && !to.getName.contains("."))
      to.mkdirs()
    if (from.isDirectory) {
      if (to.isDirectory) IO.copyDirectory(from, to)
      else                zip(from, to)
    } else {
      if (to.isDirectory) IO.copy(Seq(from -> to))
      else                zip(from, to)
    }
  }

  private def getPathInJar(output: File): (File, String) = output.getPath.split("!/") match {
    case Array(jarFile, path) => file(jarFile) -> path
    case _                    => output        -> ""
  }


  private def zip(input: File, output: File): Unit = {
    if (!input.exists()) return
    if (!output.exists()) output.getParentFile.mkdirs()
    val (outJar, jarRoot) = getPathInJar(output)
    val env = new util.HashMap[String, String]()
    env.put("create", String.valueOf(Files.notExists(outJar.toPath)))
    val jarFs     = newFileSystem(URI.create("jar:" + outJar.toPath.toUri), env)
    val inputFS   = if (input.getName.endsWith(".jar")) Some(newFileSystem(URI.create( "jar:" + input.toPath.toUri), env)) else None
    val inputPath = inputFS.map(_.getPath("/")).getOrElse(input.toPath)

    try {
      Files.walkFileTree(inputPath, new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          val newPathInJar = if (Files.isDirectory(inputPath) || jarRoot.endsWith("/")) {
            Option(jarFs.getPath(inputPath.relativize(file).toString))
              .filterNot(_.toString.isEmpty)
              .getOrElse(jarFs.getPath(file.getFileName.toString))
          } else { jarFs.getPath(jarRoot, file.getFileName.toString) } // copying file to file
          if (newPathInJar.getParent != null) Files.createDirectories(newPathInJar.getParent)
          assert(file.toString.nonEmpty)
          assert(newPathInJar.toString.nonEmpty)
          Files.copy(file, newPathInJar, StandardCopyOption.REPLACE_EXISTING)
          FileVisitResult.CONTINUE
        }
      })
    } finally {
      inputFS.foreach(_.close())
      jarFs.close()
    }
  }

  case class ProjectData(thisProject: ProjectRef,
                         cp: Classpath,
                         definedDeps: Seq[ModuleID],
                         additionalProjects: Seq[Project],
                         assembleLibraries: Boolean,
                         productDirs: Seq[File],
                         report: UpdateReport,
                         libMapping: Seq[(ModuleID, Option[String])],
                         additionalMappings: Seq[(File, String)],
                         packageMethod: PackagingMethod)


  /**
    * Extract only key-relevant parts of the ModuleId, so that mappings succeed even if they contain extra attributes
    */
  implicit class ModuleIdExt(val moduleId: ModuleID) extends AnyVal {

    def key(implicit scalaVersion: ProjectScalaVersion): ModuleKey = {
      val versionSuffix = moduleId.crossVersion match {
        case _:CrossVersion.Binary if scalaVersion.isDefined =>
          "_" + CrossVersion.binaryScalaVersion(scalaVersion.str)
        case _ => ""
      }
      ModuleKey(
        moduleId.organization % (moduleId.name + versionSuffix) % moduleId.revision,
        moduleId.extraAttributes
          .map    { case (k, v) => k.stripPrefix("e:") -> v }
          .filter { case (k, _) => k == "scalaVersion" || k == "sbtVersion" })
    }

  }

  case class ModuleKey(id:ModuleID, attributes: Map[String,String]){
    def ~==(other: ModuleKey): Boolean = id.organization == other.id.organization && id.name == other.id.name
    override def hashCode(): Int = id.organization.hashCode
    override def equals(o: scala.Any): Boolean = o match {
      case ModuleKey(_id, _attributes) =>
        id.organization.equals(_id.organization) &&
          id.name.matches(_id.name) &&
          id.revision.matches(_id.revision) &&
          attributes == _attributes
      case _ => false
    }

    override def toString: String = s"$id[${if (attributes.nonEmpty) attributes.toString else ""}]"
  }

  class TransitiveDeps(report: UpdateReport, configuration: String)(implicit scalaVersion: ProjectScalaVersion) {
    val structure: Map[ModuleKey, Seq[ModuleKey]] = buildTransitiveStructure()
    val evicted:   Seq[ModuleKey]                 = report.configurations
      .find(_.configuration.toString().contains(configuration))
      .map (_.details.flatMap(_.modules)
          .filter(m => m.evicted && m.evictedReason.get == "latest-revision")
        .map(_.module.key)
      ).getOrElse(Seq.empty)

    private def buildTransitiveStructure(): Map[ModuleKey, Seq[ModuleKey]] = {
      report.configurations.find(_.configuration.toString().contains(configuration)) match {
        case Some(conf) =>
          val edges = conf.modules.flatMap(m => m.callers.map(caller => caller.caller.key -> m.module.key))
          edges.foldLeft(Map[ModuleKey, Seq[ModuleKey]]()) {
            case (map, (caller, mod)) if caller.id.name.startsWith("temp-resolve") => map + (mod -> Seq.empty) // top level dependency
            case (map, (caller, mod)) => map + (caller -> (map.getOrElse(caller, Seq()) :+ mod))
          }
        case None => Map.empty
      }
    }

    def collectTransitiveDeps(moduleID: ModuleKey): Set[ModuleKey] = {
      val deps = structure.getOrElse(moduleID, Seq.empty)
      (deps ++ deps.flatMap(collectTransitiveDeps) :+ moduleID).toSet
    }
  }

  case class ProjectScalaVersion(libModule: Option[ModuleID]) {
    def isDefined = libModule.isDefined
    def str = libModule.map(_.revision).getOrElse("")
  }

  private def mkProjectJarPath(project: ProjectReference): String = s"lib/${extractName(project)}.jar"

  private def mkRelativeLibPath(lib: File) = s"lib/${lib.getName}"

  private def extractName(project: ProjectReference): String = {
    val str = project.toString
    val commaIdx = str.indexOf(',')
    str.substring(commaIdx+1, str.length-1)
  }
}
