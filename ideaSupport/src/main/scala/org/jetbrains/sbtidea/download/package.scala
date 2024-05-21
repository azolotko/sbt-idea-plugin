package org.jetbrains.sbtidea

import org.jetbrains.sbtidea.Keys.IntelliJPlatform as _

import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

package object download {

  case class BuildInfo(buildNumber: String, edition: IntelliJPlatform) {
    override def toString: String = s"BuildInfo($edition-$buildNumber)"
  }

  object BuildInfo {
    val LATEST_EAP_SNAPSHOT = "LATEST-EAP-SNAPSHOT"

    val EAP_CANDIDATE_SNAPSHOT_SUFFIX = "-EAP-CANDIDATE-SNAPSHOT"
    val EAP_SNAPSHOT_SUFFIX = "-EAP-SNAPSHOT"
    val SNAPSHOT_SUFFIX = "-SNAPSHOT"
  }

  def withConnection[V](url: URL)(f: => HttpURLConnection => V): V = {
    var connection: HttpURLConnection = null
    try {
      connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setConnectTimeout(5.seconds.toMillis.toInt)
      connection.setReadTimeout(30.seconds.toMillis.toInt)
      f(connection)
    } finally {
      try {
        if (connection != null) connection.disconnect()
      } catch {
        case e: Exception =>
          println(s"Failed to close connection $url: ${e.getMessage}")
      }
    }
  }

  implicit class BuildInfoOps(private val buildInfo: BuildInfo) extends AnyVal {

    /**
     * @note build number can be obtained from two places:
     *       - build.txt
     *       - product-info.json
     *       In this method we use `build.txt` because has a primitive structure
     */
    def getActualIdeaBuild(ideaRoot: Path): String = {
      val buildTxt = ideaRoot.resolve("build.txt")
      //example: `IU-241.17011.2`
      val content = new String(Files.readAllBytes(buildTxt)).trim
      content.substring(content.indexOf("-") + 1)
    }
  }

  val NotFoundHttpResponseCode = 404
}
