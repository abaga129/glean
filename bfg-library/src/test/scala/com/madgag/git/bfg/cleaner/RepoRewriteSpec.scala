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

package com.madgag.git.bfg.cleaner

import java.io.StringReader
import java.net.URLEncoder
import java.util.Properties
import java.util.regex.Pattern._

import com.madgag.git._
import com.madgag.git.bfg.GitUtil._
import com.madgag.git.bfg.cleaner.ObjectIdSubstitutor._
import com.madgag.git.bfg.cleaner.protection.ProtectedObjectCensus
import com.madgag.git.bfg.model.TreeBlobEntry
import com.madgag.git.test._
import com.madgag.textmatching.Literal
import com.madgag.textmatching.RegexReplacer._
import org.apache.commons.io.FilenameUtils
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.RawParseUtils
import org.scalatest.{FlatSpec, Matchers}

import scala.PartialFunction.condOpt
import scala.collection.convert.wrapAsScala._

class RepoRewriteSpec extends FlatSpec with Matchers {

  "Git repo" should "not explode" in {
    implicit val repo = unpackRepo("/sample-repos/example.git.zip")
    implicit val reader = repo.newObjectReader

    hasBeenProcessedByBFGBefore(repo) shouldBe false

    val blobsToRemove = Set(abbrId("06d740"))
    RepoRewriter.rewrite(repo, ObjectIdCleaner.Config(ProtectedObjectCensus(Set("HEAD")), OldIdsPublic, Seq(FormerCommitFooter), treeBlobsCleaners = Seq(new BlobRemover(blobsToRemove))))

    val allCommits = repo.git.log.all.call.toSeq

    val unwantedBlobsByCommit = allCommits.flatMap(commit => {
      val unwantedBlobs = allBlobsReachableFrom(commit).intersect(blobsToRemove).map(_.shortName)
      if (!unwantedBlobs.isEmpty) Some(commit.shortName -> unwantedBlobs) else None
    }).toMap

    unwantedBlobsByCommit shouldBe empty

    allCommits.head.getFullMessage should include(FormerCommitFooter.Key)

    hasBeenProcessedByBFGBefore(repo) shouldBe true
  }

  "Repo rewriter" should "clean commit messages even on clean branches, because commit messages may reference commits from dirty ones" in {
    implicit val repo = unpackRepo("/sample-repos/taleOfTwoBranches.git.zip")
    implicit val revWalk = new RevWalk(repo)

    def commitMessageForRev(rev: String) = repo.resolve(rev).asRevCommit.getFullMessage

    commitMessageForRev("pure") should include("6e76960ede2addbbe7e")

    RepoRewriter.rewrite(repo, ObjectIdCleaner.Config(ProtectedObjectCensus.None, OldIdsPrivate, Seq(new CommitMessageObjectIdsUpdater(OldIdsPrivate)), treeBlobsCleaners = Seq(new FileDeleter(Literal("sin")))))

    commitMessageForRev("pure") should not include "6e76960ede2addbbe7e"
  }

  it should "remove passwords" in {
    implicit val repo = unpackRepo("/sample-repos/example.git.zip")
    implicit val (revWalk, reader) = repo.singleThreadedReaderTuple

    def propertiesIn(contents: String) = {
      val p = new Properties()
      p.load(new StringReader(contents))
      p
    }

    def passwordFileContentsIn(id: ObjectId) = {
      val cleanedPasswordFile = repo.resolve(id.name + ":folder/secret-passwords.txt")
      RawParseUtils.decode(reader.open(cleanedPasswordFile).getCachedBytes)
    }

    object FileExt {
      def unapply(fileName: String) = Option(FilenameUtils.getExtension(fileName))
    }

    val blobTextModifier = new BlobTextModifier {
      override def lineCleanerFor(entry: TreeBlobEntry) = condOpt(entry.filename.string) {
        case FileExt("txt") | FileExt("scala") => """(\.password=).*""".r --> (_.group(1) + "*** PASSWORD ***")
      }

      val threadLocalObjectDBResources = repo.getObjectDatabase.threadLocalResources
    }
    val cleanedObjectMap = RepoRewriter.rewrite(repo, ObjectIdCleaner.Config(ProtectedObjectCensus(Set("HEAD")), treeBlobsCleaners = Seq(blobTextModifier)))

    val oldCommitContainingPasswords = abbrId("37bcc89")

    val cleanedCommitWithPasswordsRemoved = cleanedObjectMap(oldCommitContainingPasswords).asRevCommit

    val originalContents = passwordFileContentsIn(oldCommitContainingPasswords)
    val cleanedContents = passwordFileContentsIn(cleanedCommitWithPasswordsRemoved)

    cleanedContents should (include("science") and include("database.password="))
    originalContents should include("correcthorse")
    cleanedContents should not include "correcthorse"

    propertiesIn(cleanedContents).toMap should have size propertiesIn(originalContents).size
  }


  def textReplacementOf(parentPath: String, fileNamePrefix: String, fileNamePostfix: String, before: String, after: String) = {
    implicit val repo = unpackRepo("/sample-repos/encodings.git.zip")

    val blobTextModifier = new BlobTextModifier {
      def lineCleanerFor(entry: TreeBlobEntry) = Some(quote(before).r --> (_ => after))

      val threadLocalObjectDBResources = repo.getObjectDatabase.threadLocalResources
    }
    RepoRewriter.rewrite(repo, ObjectIdCleaner.Config(ProtectedObjectCensus.None, treeBlobsCleaners = Seq(blobTextModifier)))

    val beforeAndAfter = Seq(before, after).map(URLEncoder.encode(_, "UTF-8")).mkString("-")

    val beforeFile = s"$parentPath/$fileNamePrefix-ORIGINAL.$fileNamePostfix"
    val afterFile = s"$parentPath/$fileNamePrefix-MODIFIED-$beforeAndAfter.$fileNamePostfix"

    val cleanedFile = repo.resolve(s"master:$beforeFile")
    val expectedFile = repo.resolve(s"master:$afterFile")

    expectedFile should not be null

    cleanedFile shouldBe expectedFile
  }

  "Text modifier" should "handle the short UTF-8" in textReplacementOf("UTF-8", "bushhidthefacts", "txt", "facts", "toffee")

  it should "handle the long UTF-8" in textReplacementOf("UTF-8", "big", "scala", "good", "blessed")

  it should "handle ASCII in SHIFT JIS" in textReplacementOf("SHIFT-JIS", "japanese", "txt", "EUC", "BOOM")

  it should "handle ASCII in ISO-8859-1" in textReplacementOf("ISO-8859-1", "laparabla", "txt", "palpitando", "buscando")

  it should "handle converting Windows newlines to Unix" in textReplacementOf("newlines", "windows", "txt", "\r\n", "\n")

}
