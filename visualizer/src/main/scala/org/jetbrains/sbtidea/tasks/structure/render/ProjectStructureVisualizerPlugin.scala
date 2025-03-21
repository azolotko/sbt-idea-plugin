package org.jetbrains.sbtidea.tasks.structure.render

import org.jetbrains.sbtidea.SbtPluginLogger
import org.jetbrains.sbtidea.structure.sbtImpl.{SbtProjectData, SbtProjectStructureExtractor}
import sbt.*
import sbt.Keys.*

object ProjectStructureVisualizerPlugin extends AutoPlugin {
  import autoImport.*
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  override def projectSettings: Seq[Setting[?]] = Seq(
    printProjectGraph := {
      val rootProject = thisProjectRef.value
      val buildDeps = buildDependencies.value
      val data = dumpBuildStructureCore.?.all(ScopeFilter(inAnyProject)).value.flatten.filterNot(_ == null)
      val logger = new SbtPluginLogger(streams.value)
      val structure = new SbtProjectStructureExtractor(rootProject, data, buildDeps, logger).extract
      streams.value.log.info(new StructurePrinter().renderASCII(structure.last))
    },
    dumpBuildStructureCore := {
      SbtProjectData(
        thisProject = thisProjectRef.value,
        thisProjectName = name.in(thisProjectRef).value,
        // note, we intentionally get the runtime classpath here
        cp = managedClasspath.in(Runtime).value,
        definedDeps = libraryDependencies.in(Compile).value,
        productDirs = productDirectories.in(Compile).value,
        report = update.value
      )
    }
  )

  object autoImport {
    lazy val dumpBuildStructureCore = taskKey[SbtProjectData]("")//.withRank(Invisible)
    lazy val printProjectGraph      = taskKey[Unit]("Show ASCII project dependency graph in console")
  }
}
