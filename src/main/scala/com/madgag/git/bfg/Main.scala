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

package com.madgag.git.bfg

import cleaner._
import cleaner.TreeBlobsCleaner.Kit
import model._
import model.TreeBlobEntry
import org.eclipse.jgit.lib._
import org.eclipse.jgit.storage.file.{WindowCacheConfig, WindowCache, FileRepository}
import java.io.File
import GitUtil._
import textmatching.RegexReplacer._
import util.matching.Regex
import com.madgag.globs.openjdk.Globs
import io.Source
import collection.immutable.SortedSet
import scopt.immutable.OptionParser
import scala.Some
import com.madgag.git.bfg.GitUtil.SizedObject

case class CMDConfig(stripBiggestBlobs: Option[Int] = None,
                     stripBlobsBiggerThan: Option[Int] = None,
                     protectBlobsFromRevisions: Set[String] = Set("HEAD"),
                     deleteFiles: Option[String] = None,
                     filterFiles: String = "*",
                     replaceBannedStrings: Traversable[String] = List.empty,
                     replaceBannedRegex: Traversable[Regex] = List.empty,
                     gitdir: Option[File] = None) {

  lazy val fileDeleterOption: Option[TreeBlobsCleaner] = deleteFiles.map { glob =>
    val filePattern = Globs.toUnixRegexPattern(glob).r
    new TreeBlobsCleaner {
      def fixer(kit: Kit) = tb => TreeBlobs(tb.entries.filterNot(e => filePattern.matches(e.filename.string)))
    }
  }

  lazy val lineModifierOption: Option[String => String] = {
    val allRegex = replaceBannedRegex ++ replaceBannedStrings.map(Regex.quoteReplacement(_).r)
    allRegex.map(regex => regex --> (_ => "***REMOVED***")).reduceOption((f,g) => Function.chain(Seq(f,g)))
  }

  lazy val filterFilesPredicate = {
    val GlobPattern = Globs.toUnixRegexPattern(filterFiles).r

    (fn:FileName) => fn.string match { case GlobPattern() => true ; case _ => false }
  }
}

object Main extends App {

  val wcConfig: WindowCacheConfig = new WindowCacheConfig()
  wcConfig.setStreamFileThreshold(1024 * 1024)
  WindowCache.reconfigure(wcConfig)

  val parser = new OptionParser[CMDConfig]("bfg") {
    def options = Seq(
      opt("b", "strip-blobs-bigger-than", "<size>", "strip blobs bigger than X (eg '128K', '1M', etc)") {
        (v: String, c: CMDConfig) => c.copy(stripBlobsBiggerThan = Some(ByteSize.parse(v)))
      },
      intOpt("B", "strip-biggest-blobs", "NUM", "strip the top NUM biggest blobs") {
        (v: Int, c: CMDConfig) => c.copy(stripBiggestBlobs = Some(v))
      },
      opt("p", "protect-blobs-from", "<refs>", "protect blobs that appear in the most recent versions of the specified refs") {
        (v: String, c: CMDConfig) => c.copy(protectBlobsFromRevisions = v.split(',').toSet)
      },
      opt("d", "delete-files", "<glob>", "delete files with the specified names (eg '*.class', '*.{txt,log}' - matches on file name, not path)") {
        (v: String, c: CMDConfig) => c.copy(deleteFiles = Some(v))
      },
      opt("f", "filter-contents-of", "<glob>", "filter only files with the specified names (eg '*.txt', '*.{properties}')") {
        (v: String, c: CMDConfig) => c.copy(filterFiles = v)
      },
      opt("rs", "replace-banned-strings", "<banned-strings-file>", "replace strings specified in file, one string per line") {
        (v: String, c: CMDConfig) => c.copy(replaceBannedStrings = Source.fromFile(v).getLines().toSeq)
      },
      opt("rr", "replace-banned-regex", "<banned-regex-file>", "replace regex specified in file, one regex per line") {
        (v: String, c: CMDConfig) => c.copy(replaceBannedRegex = Source.fromFile(v).getLines().map(_.r).toSeq)
      },
      arg("<repo>", "repo to clean") {
        (v: String, c: CMDConfig) =>
          val dir = new File(v).getCanonicalFile
          val gitdir = resolveGitDirFor(dir)
          if (gitdir == null || !gitdir.exists)
            throw new IllegalArgumentException("'%s' is not a valid Git repository.".format(dir.getAbsolutePath))
          c.copy(gitdir = Some(gitdir))
      }
    )
  }


  parser.parse(args, CMDConfig()) map {
    config =>
      println(config)

      implicit val repo = new FileRepository(config.gitdir.get)
      implicit val progressMonitor = new TextProgressMonitor()

      println("Using repo : " + repo.getDirectory.getAbsolutePath)
      val objectProtection = ObjectProtection(config.protectBlobsFromRevisions)
      println("Found " + objectProtection.fixedObjectIds.size + " objects to protect")

      val blobRemoverOption = {

          val sizeBasedBlobTargetSources = Seq(
            config.stripBlobsBiggerThan.map(threshold => (s: Stream[SizedObject]) => s.takeWhile(_.size > threshold)),
            config.stripBiggestBlobs.map(num => (s: Stream[SizedObject]) => s.take(num))
          ).flatten

          sizeBasedBlobTargetSources match {
            case sources if sources.size > 0 =>
              Timing.measureTask("Finding target blobs", ProgressMonitor.UNKNOWN) {
                val biggestUnprotectedBlobs = biggestBlobs(repo).filterNot(o => objectProtection.blobIds(o.objectId))
                val sizedBadIds = SortedSet(sources.flatMap(_(biggestUnprotectedBlobs)): _*)
                println("Found " + sizedBadIds.size + " blob ids to remove biggest=" + sizedBadIds.max.size + " smallest=" + sizedBadIds.min.size)
                println("Total size (unpacked)=" + sizedBadIds.map(_.size).sum)
                Some(new BlobReplacer(sizedBadIds.map(_.objectId)))
              }
            case _ => None
          }
        }

      val blobTextModifierOption: Option[BlobTextModifier] = config.lineModifierOption.map(replacer => new BlobTextModifier {
        def lineCleanerFor(entry: TreeBlobEntry) = if (config.filterFilesPredicate(entry.filename)) Some(replacer) else None
      })

      val treeBlobCleaners = TreeBlobsCleaner.chain(Seq(blobRemoverOption, config.fileDeleterOption, blobTextModifierOption).flatten)
      RepoRewriter.rewrite(repo, treeBlobCleaners, objectProtection)
  }

  object ByteSize {
    val magnitudeChars = List('B', 'K', 'M', 'G')

    def parse(v: String): Int = {

      magnitudeChars.indexOf(v.takeRight(1)(0).toUpper) match {
        case -1 => throw new IllegalArgumentException("Size unit is missing (ie %s)".format(magnitudeChars.mkString(", ")))
        case index => v.dropRight(1).toInt << (index * 10)
      }
    }
  }

}