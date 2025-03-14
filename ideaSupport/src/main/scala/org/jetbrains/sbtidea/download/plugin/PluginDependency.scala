package org.jetbrains.sbtidea.download.plugin

import org.jetbrains.sbtidea.IntellijPlugin
import org.jetbrains.sbtidea.download.BuildInfo
import org.jetbrains.sbtidea.download.api.*

case class PluginDependency(
  plugin: IntellijPlugin,
  buildInfo: BuildInfo,
  dependsOn: Seq[UnresolvedArtifact] = Seq.empty
)(implicit private val ctx: IdeInstallationProcessContext, repo: PluginRepoApi, localRegistry: LocalPluginRegistryApi) extends UnresolvedArtifact {
  override type U = PluginDependency
  override type R = PluginArtifact
  override protected def usedResolver: PluginResolver = new PluginResolver(resolveSettings = plugin.resolveSettings)
  override def toString: String = s"PluginDependency($plugin)"
}