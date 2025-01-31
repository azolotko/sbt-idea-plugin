package org.jetbrains.sbtidea.download.plugin

import org.jetbrains.annotations.TestOnly

import java.io.{BufferedInputStream, BufferedOutputStream, FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import java.nio.file.{Files, Path}
import java.util.function.Consumer
import org.jetbrains.sbtidea.download.plugin.LocalPluginRegistry.extractPluginMetaData
import org.jetbrains.sbtidea.packaging.artifact.using
import org.jetbrains.sbtidea.{pathToPathExt, PluginLogger => log}
import sbt._

class PluginIndexImpl(ideaRoot: Path) extends PluginIndex {

  import PluginIndexImpl._
  import scala.collection.mutable

  type PluginId = String
  type PluginFolder = String
  type PersistentRepr = Array[String]
  type Repr = mutable.HashMap[PluginId, (Path, PluginDescriptor)]

  private val indexFile: Path = ideaRoot / INDEX_FILENAME

  lazy val index: Repr = {
    if (indexFile.exists) {
      try {
        loadFromFile()
      } catch {
        case e: Throwable =>
          log.warn(s"Failed to load plugin index from disk: $e")
          indexFile.toFile.delete()
          buildAndSaveIndex()
      }
    } else {
      buildAndSaveIndex()
    }
  }

  private def buildAndSaveIndex() = {
    val fromPluginsDir = buildFromPluginsDir()
    try {
      saveToFile(fromPluginsDir)
    } catch {
      case e: Throwable =>
        log.warn(s"Failed to write back plugin index: $e")
    }
    fromPluginsDir
  }

  override def put(descriptor: PluginDescriptor, installPath: Path): Unit = {
    index += descriptor.id -> (installPath, descriptor)
    saveToFile(index)
  }

  override def contains(id: String): Boolean =
    index.contains(id)

  override def getInstallRoot(id: String): Option[Path] =
    index.get(id).map(_._1)

  override def getPluginDescriptor(id: String): Option[PluginDescriptor] =
    index.get(id).map(_._2)

  override def getAllDescriptors: Seq[PluginDescriptor] = index.values.map(_._2).toSeq

  private def loadFromFile(): Repr = {
    import PluginDescriptor._
    val buffer = new Repr
    using(new FileInputStream(indexFile.toFile)) { fis =>
      using(new ObjectInputStream(new BufferedInputStream(fis))) { stream =>
        val version = stream.readInt()
        val size = stream.readInt()
        if (version != INDEX_VERSION)
          throw new WrongIndexVersionException(version)
        val paths = stream.readObject().asInstanceOf[Array[String]]
        val descriptors = stream.readObject().asInstanceOf[Array[String]]
        assert(paths.length == descriptors.length && paths.length == size,
          s"Data size mismatch: ${paths.length} - ${descriptors.length} $size")
        val data = paths
          .zip(descriptors)
          .map { case (path, str)         => path -> load(str) }
          .map { case (path, descriptor)  => descriptor.id -> (ideaRoot / path, descriptor) }
          .toMap
        buffer ++= data
        buffer
      }
    }
  }

  private def saveToFile(idx: Repr): Unit = {
    using(new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(indexFile.toFile)))) { stream =>
      stream.writeInt(INDEX_VERSION)
      stream.writeInt(idx.size)
      val values = idx.values
      val paths           = values.map(x => ideaRoot.relativize(x._1).toString).toArray
      val descriptorsStr  = values.map(_._2.toXMLStr).toArray
      stream.writeObject(paths)
      stream.writeObject(descriptorsStr)
    }
  }

  private def buildFromPluginsDir(): Repr = {
    val buffer = new Repr
    class IndexBuilder extends Consumer[Path] {
      override def accept(path: Path): Unit = {
        val pluginMetaData = extractPluginMetaData(path)
        pluginMetaData match {
          case Left(error) =>
            log.warn(s"Failed to add plugin to index: $error")
          case Right(descriptor) =>
            buffer.put(descriptor.id, path -> descriptor)
        }
      }
    }
    Files.list(ideaRoot.resolve("plugins")).forEach(new IndexBuilder)
    buffer
  }
}

object PluginIndexImpl {

  class WrongIndexVersionException(fileIndex: Int)
    extends RuntimeException(s"Index version in file $fileIndex is different from current $INDEX_VERSION")

  @TestOnly val INDEX_FILENAME = "plugins.idx"
  private val INDEX_VERSION = 2
}