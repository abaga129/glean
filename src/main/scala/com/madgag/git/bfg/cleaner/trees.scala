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

import org.eclipse.jgit.lib.{ObjectDatabase, ObjectId}
import com.madgag.git.bfg.model._
import org.eclipse.jgit.diff.RawText
import java.io.{LineNumberInputStream, InputStream, ByteArrayOutputStream}
import org.eclipse.jgit.lib.Constants._
import com.madgag.git.bfg.cleaner.TreeBlobsCleaner.Kit
import scalaz.Memo
import com.madgag.git.bfg.MemoUtil


object TreeBlobsCleaner {

  class Kit(objectDB: ObjectDatabase) {
    lazy val objectReader = objectDB.newReader

    private lazy val inserter = objectDB.newInserter

    lazy val blobInserter = new BlobInserter {
      def insert(length: Long, in: InputStream) = inserter.insert(OBJ_BLOB, length, in)

      def insert(data: Array[Byte]) = inserter.insert(OBJ_BLOB, data)
    }
  }

  def chain(cleaners: Seq[TreeBlobsCleaner]) = new TreeBlobsCleaner {
    override def fixer(kit: TreeBlobsCleaner.Kit) = Function.chain(cleaners.map(_.fixer(kit)))
  }
}

trait TreeBlobsCleaner {
  def fixer(kit: TreeBlobsCleaner.Kit): (TreeBlobs => TreeBlobs)
}

class BlobRemover(blobIds: Set[ObjectId]) extends TreeBlobsCleaner {
  override def fixer(kit: Kit) = _.entries.filter(e => !blobIds.contains(e.objectId))
}

class BlobReplacer(badBlobs: Set[ObjectId]) extends TreeBlobsCleaner {
  def fixer(kit: Kit) = { treeBlobs =>
    val updatedEntryMap = treeBlobs.entryMap.map {
      case (filename, (mode, oid)) if badBlobs.contains(oid) =>
        FileName(filename + ".REMOVED.git-id") ->(RegularFile, kit.blobInserter.insert(oid.name.getBytes))
      case e => e
    }
    TreeBlobs(updatedEntryMap)
  }
}


trait TreeBlobModifier extends TreeBlobsCleaner {

  val memo: Memo[TreeBlobEntry, TreeBlobEntry] = MemoUtil.concurrentCleanerMemo(Set.empty)

  override def fixer(kit: Kit) = treeBlobs => TreeBlobs(treeBlobs.entries.map(memo {
    entry =>
      val (mode, objectId) = fix(entry, kit)
      TreeBlobEntry(entry.filename, mode, objectId)
  }))

  def fix(entry: TreeBlobEntry, kit: Kit): (BlobFileMode, ObjectId) // implementing code can not safely know valid filename
}


trait BlobTextModifier extends TreeBlobModifier {

  def lineCleanerFor(entry: TreeBlobEntry): Option[String => String]

  override def fix(entry: TreeBlobEntry, kit: Kit) = {

    def filterTextIn(e: TreeBlobEntry, lineCleaner: String => String): TreeBlobEntry = {
      val objectLoader = kit.objectReader.open(e.objectId)
      if (objectLoader.isLarge) {
        println("LARGE")
        e
      } else {
        val cachedBytes = objectLoader.getCachedBytes
        val rawText = new RawText(cachedBytes)

        def isDirty(line: String) = lineCleaner(line) != line

        val originalLines = (0 until rawText.size).view.map(l => rawText.getString(l, l + 1, false))

        val firstDirtyLine = originalLines.indexWhere(isDirty)

        if (firstDirtyLine == -1) {
          e
        } else {
          val b = new ByteArrayOutputStream(cachedBytes.length)
          // TODO - use firstDirtyLine
          originalLines.map(lineCleaner).foreach(line => b.write(line.getBytes))

          val oid = kit.blobInserter.insert(b.toByteArray)

          e.copy(objectId = oid)
        }
      }
    }

    lineCleanerFor(entry) match {
      case Some(lineCleaner) => filterTextIn(entry, lineCleaner).withoutName
      case None => entry.withoutName
    }
  }
}