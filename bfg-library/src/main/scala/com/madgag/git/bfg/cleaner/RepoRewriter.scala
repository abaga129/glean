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

import org.eclipse.jgit.revwalk.{RevCommit, RevWalk}
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.revwalk.RevSort._
import com.madgag.git.bfg.Timing
import concurrent.future
import concurrent.ExecutionContext.Implicits.global
import scala.collection.convert.wrapAll._
import com.madgag.git._
import org.eclipse.jgit.lib.ObjectId

/*
Encountering a blob ->
BIG-BLOB-DELETION : Either 'good' or 'delete'   - or possibly replace, with a different filename (means tree-level)
PASSWORD-REMOVAL : Either 'good' or 'replace'

Encountering a tree ->
BIG-BLOB-DELETION : Either 'good' or 'replace'   - possibly adding with a different placeholder blob entry
PASSWORD-REMOVAL : Either 'good' or 'replace' - replacing one blob entry with another

So if we encounter a tree, we are unlikely to want to remove that tree entirely...
SHOULD WE JUST DISALLOW THAT?
Obviously, a Commit HAS to have a tree, so it's dangerous to allow a None response to tree transformation

An objectId must be either GOOD or BAD, and we must never translate *either* kind of id into a BAD

User-customisation interface: TreeBlobs => TreeBlobs

User gets no say in adding, renaming, removing directories

TWO MAIN USE CASES FOR HISTORY-CHANGING ARE:
1: GETTING RID OF BIG BLOBS
2: REMOVING PASSWORDS IN HISTORICAL FILES

possible other use-case: fixing committer names - and possibly removing passwords from commits? (could possibly just be done with rebase)

Why else would you want to rewrite HISTORY? Many other changes (ie putting a directory one down) need only be applied
in a new commit, we don't care about history.

When updating a Tree, the User has no right to muck with sub-trees. They can only alter the blob contents.
 */

object RepoRewriter {

  def rewrite(repo: org.eclipse.jgit.lib.Repository, objectIdCleanerConfig: ObjectIdCleaner.Config): Map[ObjectId, ObjectId] = {
    assert(!repo.getAllRefs.isEmpty, "Can't find any refs in repo at " + repo.getDirectory.getAbsolutePath)

    implicit val refDatabase = repo.getRefDatabase

    val reporter: Reporter = new CLIReporter(repo)
    implicit val progressMonitor = reporter.progressMonitor

    val allRefs = repo.getAllRefs.values

    def createRevWalk: RevWalk = {

      val revWalk = new RevWalk(repo)

      revWalk.sort(TOPO) // crucial to ensure we visit parents BEFORE children, otherwise blow stack
      revWalk.sort(REVERSE, true) // we want to start with the earliest commits and work our way up...

      val startCommits = allRefs.map(_.targetObjectId.asRevObject(revWalk)).collect { case c: RevCommit => c }

      revWalk.markStart(startCommits)
      revWalk
    }

    implicit val revWalk = createRevWalk
    implicit val reader = revWalk.getObjectReader

    reporter.reportRefsForScan(allRefs)

    val objectIdCleaner = new ObjectIdCleaner(objectIdCleanerConfig, repo.getObjectDatabase, revWalk)

    reporter.reportObjectProtection(objectIdCleanerConfig, objectIdCleaner)

    val commits = revWalk.toList

    reporter.reportCleaningStart(commits)

    Timing.measureTask("Cleaning commits", commits.size) {
      future {
        commits.par.foreach {
          commit => objectIdCleaner(commit.getTree)
        }
      }

      commits.foreach {
        commit =>
          objectIdCleaner(commit)
          progressMonitor update 1
      }
    }

    val refUpdateCommands = for (ref <- repo.getAllRefs.values if !ref.isSymbolic;
                                 (oldId, newId) <- objectIdCleaner.substitution(ref.getObjectId)
    ) yield new ReceiveCommand(oldId, newId, ref.getName)

    if (refUpdateCommands.isEmpty) {
      println("\nBFG aborting: No refs to update - no dirty commits found??\n")
    } else {
      reporter.reportRefUpdateStart(refUpdateCommands)

      Timing.measureTask("...Ref update", refUpdateCommands.size) {
        refDatabase.newBatchUpdate.setAllowNonFastForwards(true).addCommand(refUpdateCommands).execute(revWalk, progressMonitor)
      }

      reporter.reportResults(commits, objectIdCleaner)
    }

    objectIdCleaner.cleanedObjectMap()
  }

}
