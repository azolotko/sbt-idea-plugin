package org.jetbrains.sbtidea

import java.net.URL
import scala.util.matching.Regex

sealed trait IntellijPlugin {
  var resolveSettings: IntellijPlugin.Settings = IntellijPlugin.Settings()
}

object IntellijPlugin {
  /**
   * @param name not used but might be a helpful readable reminder
   */
  final case class Url(name: Option[String], url: URL) extends IntellijPlugin {
    override def toString: String = url.toString
  }

  sealed trait WithKnownId extends IntellijPlugin {
    def id: String
  }

  final case class Id(override val id: String, version: Option[String], channel: Option[String]) extends WithKnownId {
    override def toString: String = id
  }

  final case class IdWithCustomUrl(override val id: String, downloadUrl: URL) extends WithKnownId {
    override def toString: String = id
  }

  final case class BundledFolder(name: String) extends IntellijPlugin


  /**
   * [name]:url
   *
   * Examples:
   *  - https://org.example
   *  - my-plugin-name:https://org.example
   */
  val UrlRegex: Regex = "^(?:([^:]+):)??(https?://.+)$".r

  /**
   * id:[version]:[channel]
   *
   * Examples:
   *  - plugin-id
   *  - plugin-id:2023.3.1
   *  - plugin-id:2023.3.1:eap
   */
  val IdRegex: Regex = "^([^:]+):?([\\w.-]+)?:?(\\w+)?$".r

  /**
   * id:[channel]:url
   *
   * Examples:
   *  - plugin-id:https://org.example
   *  - plugin-id:2023.3.1:https://org.example //!!! version is not actually used, but it's parsed not to break old usages
   */
  val IdWithCustomUrlRegex: Regex = "^([^:]+):?([\\w.-]+)?:?(https?://.+)$".r

  case class Settings(
    transitive: Boolean = true,
    optionalDeps: Boolean = true,
    excludedIds: Set[String] = Set.empty
  )
}