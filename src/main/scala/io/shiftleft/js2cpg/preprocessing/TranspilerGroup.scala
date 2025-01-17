package io.shiftleft.js2cpg.preprocessing

import better.files.File
import io.shiftleft.js2cpg.core.Config
import io.shiftleft.js2cpg.io.ExternalCommand
import org.slf4j.LoggerFactory

import java.nio.file.Path
import scala.util.{Failure, Success}

case class TranspilerGroup(override val config: Config, override val projectPath: Path, transpilers: Seq[Transpiler])
    extends Transpiler {

  private val logger = LoggerFactory.getLogger(getClass)

  private val BABEL_PLUGINS: String =
    "@babel/core " +
      "@babel/cli " +
      "@babel/preset-env " +
      "@babel/preset-flow " +
      "@babel/preset-react " +
      "@babel/preset-typescript " +
      "@babel/plugin-proposal-class-properties " +
      "@babel/plugin-proposal-private-methods " +
      "@babel/plugin-proposal-object-rest-spread " +
      "@babel/plugin-proposal-nullish-coalescing-operator " +
      "@babel/plugin-transform-runtime " +
      "@babel/plugin-transform-property-mutators"

  private def installPlugins(): Boolean = {
    val command = if (pnpmAvailable(projectPath)) {
      s"${TranspilingEnvironment.PNPM_ADD} $BABEL_PLUGINS && ${TranspilingEnvironment.PNPM_INSTALL}"
    } else if (yarnAvailable()) {
      s"${TranspilingEnvironment.YARN_ADD} $BABEL_PLUGINS && ${TranspilingEnvironment.YARN_INSTALL}"
    } else {
      s"${TranspilingEnvironment.NPM_INSTALL} $BABEL_PLUGINS && ${TranspilingEnvironment.NPM_INSTALL}"
    }
    logger.info("Installing project dependencies and plugins. That will take a while.")
    logger.debug(s"\t+ Installing plugins with command '$command' in path '$projectPath'")
    ExternalCommand.run(command, projectPath.toString) match {
      case Success(_) =>
        logger.info("\t+ Plugins installed")
        true
      case Failure(exception) =>
        logger.error("\t- Failed to install plugins", exception)
        false
    }
  }

  override def shouldRun(): Boolean = transpilers.exists(_.shouldRun())

  override protected def transpile(tmpTranspileDir: Path): Boolean = {
    if (installPlugins()) {
      transpilers.takeWhile(_.run(tmpTranspileDir)).length == transpilers.length
    } else {
      true
    }
  }

  override def validEnvironment(): Boolean = transpilers.forall(_.validEnvironment())

  override protected def logExecution(): Unit = {
    logger.info(s"Downloading / installing plugins in '${File(projectPath).name}'")
  }
}
