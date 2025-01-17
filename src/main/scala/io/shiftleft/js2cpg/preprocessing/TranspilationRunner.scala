package io.shiftleft.js2cpg.preprocessing

import better.files.File
import better.files.File.LinkOptions
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.shiftleft.js2cpg.core.Config
import io.shiftleft.js2cpg.io.ExternalCommand
import io.shiftleft.js2cpg.io.FileDefaults
import io.shiftleft.js2cpg.io.FileDefaults._
import io.shiftleft.js2cpg.io.FileUtils
import io.shiftleft.js2cpg.parser.PackageJsonParser
import org.slf4j.LoggerFactory

import java.nio.file.{Path, StandardCopyOption}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

class TranspilationRunner(projectPath: Path, tmpTranspileDir: Path, config: Config, subDir: Option[Path] = None) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val transpilers: Seq[Transpiler] = createTranspilers()

  private val DEPS_TO_KEEP: List[String] = List("@vue", "vue", "nuxt", "@babel")

  private def createTranspilers(): Seq[Transpiler] = {
    // We always run the following transpilers by default when not stated otherwise in the Config.
    // This includes running them for sub-projects.
    val baseTranspilers = TranspilerGroup(
      config,
      projectPath,
      Seq(
        new TypescriptTranspiler(config, projectPath, subDir = subDir),
        new BabelTranspiler(config, projectPath, subDir = subDir)
      )
    )

    // When we got no sub-project, we also run the following ones:
    if (subDir.isEmpty) {
      val otherTranspilers = Seq(
        new VueTranspiler(config, projectPath),
        new EjsTranspiler(config, projectPath),
        new PugTranspiler(config, projectPath)
      )
      val base = baseTranspilers.copy(transpilers =
        baseTranspilers.transpilers.prepended(new NuxtTranspiler(config, projectPath))
      )
      base +: otherTranspilers
    } else {
      Seq(baseTranspilers)
    }
  }

  private def extractNpmRcModules(npmrc: File): Seq[String] = {
    if (npmrc.exists) {
      val npmrcContent = FileUtils.readLinesInFile(npmrc.path)
      npmrcContent.collect {
        case line if line.contains(FileDefaults.REGISTRY_MARKER) =>
          line.substring(0, line.indexOf(FileDefaults.REGISTRY_MARKER))
      }
    } else {
      Seq.empty
    }
  }

  def handlePrivateModules(): List[(Path, Path)] = {
    val project           = File(config.srcDir)
    val nodeModulesFolder = project / NODE_MODULES_DIR_NAME
    if (!nodeModulesFolder.exists) {
      List.empty
    } else {
      val privateModulesToCopy = config.privateDeps ++ extractNpmRcModules(project / NPMRC_NAME)
      if (privateModulesToCopy.nonEmpty) {
        val slPrivateDir = File(projectPath) / PRIVATE_MODULES_DIR_NAME
        slPrivateDir.createDirectoryIfNotExists()

        val nodeModulesFolderContent =
          nodeModulesFolder.collectChildren(_.isDirectory, maxDepth = 1).toSet

        val foldersToCopy = privateModulesToCopy.collect {
          case module if nodeModulesFolderContent.exists(_.name.startsWith(module)) =>
            nodeModulesFolderContent.filter(f => f.name.startsWith(module))
          case module =>
            logger.debug(
              s"Could not find '$module' in '$nodeModulesFolder'. " +
                s"Ensure that npm authentication to your private registry is working " +
                s"to use private namespace analysis feature"
            )
            Set.empty
        }.flatten

        foldersToCopy.foreach { folder =>
          logger.debug(s"Copying private module '${folder.name}' to '$slPrivateDir'.")
          Try(
            folder.copyToDirectory(slPrivateDir)(
              linkOptions = LinkOptions.noFollow,
              copyOptions = Seq(StandardCopyOption.REPLACE_EXISTING) ++ LinkOptions.noFollow
            )
          ).tap(
            _.failed
              .foreach(
                logger
                  .debug(s"Unable to copy private module '${folder.name}' to '$slPrivateDir': ", _)
              )
          )
        }

        FileUtils
          .getFileTree(slPrivateDir.path, config, List(JS_SUFFIX, MJS_SUFFIX))
          .map(f => (f, slPrivateDir.path))
      } else List.empty
    }
  }

  private def withTemporaryPackageJson(workUnit: () => Unit): Unit = {
    val packageJson = File(projectPath) / PackageJsonParser.PACKAGE_JSON_FILENAME
    if (config.optimizeDependencies && packageJson.exists) {
      // move lock files out of the way
      PackageJsonParser.LOCKFILES.map(File(projectPath, _)).collect {
        case lockFile if lockFile.exists =>
          lockFile.renameTo(lockFile.pathAsString + ".bak")
      }
      // pnpm workspace config file is not required as we manually descent into sub-project
      File(projectPath, PackageJsonParser.PNPM_WS_FILENAME).delete(swallowIOExceptions = true)
      File(projectPath, PackageJsonParser.NPM_SHRINKWRAP_FILENAME).delete(swallowIOExceptions = true)

      // create a temporary package.json without dependencies
      val originalContent = FileUtils.readLinesInFile(packageJson.path).mkString("\n")
      val mapper          = new ObjectMapper()
      val json            = mapper.readTree(PackageJsonParser.removeComments(originalContent))
      val jsonObject      = json.asInstanceOf[ObjectNode]

      // remove all project specific dependencies (only keep the ones required for transpiling)
      PackageJsonParser.PROJECT_DEPENDENCIES.foreach { dep =>
        Option(jsonObject.get(dep).asInstanceOf[ObjectNode]).foreach { depNode =>
          val fieldsToRemove =
            depNode
              .fieldNames()
              .asScala
              .toList
              .filterNot(f => DEPS_TO_KEEP.exists(f.startsWith))
          fieldsToRemove.foreach(depNode.remove)
        }
      }
      // remove project specific engine restrictions and script hooks
      jsonObject.remove("engines")
      jsonObject.remove("scripts")

      packageJson.writeText(mapper.writeValueAsString(json))

      // run the transpilers
      workUnit()

      // remove freshly created lock files from transpiler runs
      PackageJsonParser.LOCKFILES.map(File(projectPath, _)).foreach(_.delete(swallowIOExceptions = true))

      // restore the original package.json
      packageJson.writeText(originalContent)

      // restore lock files
      PackageJsonParser.LOCKFILES.map(f => File(projectPath, f + ".bak")).collect {
        case lockFile if lockFile.exists =>
          lockFile.renameTo(lockFile.pathAsString.stripSuffix(".bak"))
      }
    } else {
      workUnit()
    }
  }

  def execute(): Unit = {
    if (transpilers.exists(_.shouldRun())) {
      if (!transpilers.headOption.exists(_.validEnvironment())) {
        val errorMsg =
          s"""npm is not available in your environment. Please install npm and node.js.
            |Also please check if it is set correctly in your systems PATH variable.
            |Your PATH is: '${ExternalCommand.ENV_PATH_CONTENT}'
            |""".stripMargin
        logger.error(errorMsg)
        System.exit(1)
      }
      withTemporaryPackageJson(() => transpilers.takeWhile(_.run(tmpTranspileDir)))
    }
  }

}
