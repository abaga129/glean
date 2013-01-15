/*
 * Copyright (c) 2012 Roberto Tyley
 *
 * This file is part of 'BFG Repo-Cleaner' - a tool for removing large
 * or troublesome blobs from Git repositories.
 *
 * BFG Repo-Cleaner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BFG Repo-Cleaner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/ .
 */

package com.madgag.git.bfg.cli

import org.eclipse.jgit.storage.file.{WindowCacheConfig, WindowCache}
import com.madgag.git.bfg.cleaner._

object Main extends App {

  val wcConfig: WindowCacheConfig = new WindowCacheConfig()
  wcConfig.setStreamFileThreshold(1024 * 1024)
  WindowCache.reconfigure(wcConfig)

  CLIConfig.parser.parse(args, CLIConfig()) map {
    config =>
      println(config)

      implicit val repo = config.repo

      println("Using repo : " + repo.getDirectory.getAbsolutePath)

      println("Found " + config.objectProtection.fixedObjectIds.size + " objects to protect")

      RepoRewriter.rewrite(repo, config.treeBlobCleaners, config.objectProtection, config.objectChecker)
  }

}