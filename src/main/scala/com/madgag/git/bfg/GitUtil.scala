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

import org.eclipse.jgit.revwalk._
import org.eclipse.jgit.lib._
import org.eclipse.jgit.lib.ObjectReader.OBJ_ANY
import org.eclipse.jgit.treewalk.TreeWalk
import collection.mutable
import org.eclipse.jgit.storage.file.{WindowCache, WindowCacheConfig, ObjectDirectory, FileRepository}
import scala.collection.convert.wrapAsScala._
import java.io.File
import scala.{Long, Some}
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.lib.Constants.OBJ_BLOB
import com.madgag.git.bfg.cleaner._
import org.eclipse.jgit.api.Git

object ObjectId {
  def apply(str: String) = org.eclipse.jgit.lib.ObjectId.fromString(str)
}

trait CleaningMapper[V] extends Cleaner[V] {
  def isDirty(v: V) = apply(v) != v

  def substitution(oldId: V): Option[(V, V)] = {
    val newId = apply(oldId)
    if (newId == oldId) None else Some((oldId, newId))
  }

  def replacement(oldId: V): Option[V] = {
    val newId = apply(oldId)
    if (newId == oldId) None else Some(newId)
  }
}

object GitUtil {

  def tweakStaticJGitConfig {
    val wcConfig: WindowCacheConfig = new WindowCacheConfig()
    wcConfig.setStreamFileThreshold(1024 * 1024)
    WindowCache.reconfigure(wcConfig)
  }

  implicit def cleaner2CleaningMapper[V](f: Cleaner[V]): CleaningMapper[V] = new CleaningMapper[V] {
    def apply(v: V) = f(v)
  }

  implicit def fileRepository2ObjectDirectory(repo: FileRepository): ObjectDirectory = repo.getObjectDatabase

  def abbrId(str: String)(implicit reader: ObjectReader): ObjectId = reader.resolveExistingUniqueId(AbbreviatedObjectId.fromString(str)).get

  implicit class RichRepo(repo: Repository) {
    lazy val git = new Git(repo)
  }

  implicit class RichRevObject(revObject: RevObject) {
    lazy val typeString = Constants.typeString(revObject.getType)
  }

  implicit class RichObjectId(objectId: AnyObjectId) {
    def open(implicit objectReader: ObjectReader): ObjectLoader = objectReader.open(objectId)

    def asRevObject(implicit revWalk: RevWalk) = revWalk.parseAny(objectId)

    def asRevCommit(implicit revWalk: RevWalk) = revWalk.parseCommit(objectId)

    def asRevTag(implicit revWalk: RevWalk) = revWalk.parseTag(objectId)

    def asRevTree(implicit revWalk: RevWalk) = revWalk.parseTree(objectId)

    lazy val shortName = objectId.getName.take(8)
  }

  implicit class RichObjectReader(reader: ObjectReader) {
    def resolveUniquely(id: AbbreviatedObjectId): Option[ObjectId] = Some(id).map(reader.resolve).filter(_.size == 1).map(_.toSeq.head)

    def resolveExistingUniqueId(id: AbbreviatedObjectId) = resolveUniquely(id).filter(reader.has)
  }


  def resolveGitDirFor(folder: File) = Option(RepositoryCache.FileKey.resolve(folder, FS.detect)).filter(_.exists())

  def treeOrBlobPointedToBy(revObject: RevObject)(implicit revWalk: RevWalk): Either[RevBlob, RevTree] = revObject match {
    case commit: RevCommit => Right(commit.getTree)
    case tree: RevTree => Right(tree)
    case blob: RevBlob => Left(blob)
    case tag: RevTag => treeOrBlobPointedToBy(tag.getObject)
  }

  def allBlobsUnder(tree: RevTree)(implicit repo: Repository): Set[ObjectId] = {
    val treeWalk = new TreeWalk(repo)
    treeWalk.setRecursive(true)
    treeWalk.addTree(tree)
    val protectedIds = mutable.Set[ObjectId]()
    while (treeWalk.next) {
      protectedIds += treeWalk.getObjectId(0)
    }
    protectedIds.toSet
  }

  // use ObjectWalk instead ??
  def allBlobsReachableFrom(revisions: Set[String])(implicit repo: Repository): Set[ObjectId] = {
    implicit val revWalk = new RevWalk(repo)

    revisions.map(repo.resolve).toSet.map {
      objectId: ObjectId => allBlobsReachableFrom(objectId.asRevObject)
    } reduce (_ ++ _)
  }

  def allBlobsReachableFrom(revObject: RevObject)(implicit repo: Repository): Set[ObjectId] = revObject match {
    case commit: RevCommit => allBlobsUnder(commit.getTree)
    case tree: RevTree => allBlobsUnder(tree)
    case blob: RevBlob => Set(blob)
    case tag: RevTag => allBlobsReachableFrom(tag.getObject)
  }

  case class SizedObject(objectId: ObjectId, size: Long) extends Ordered[SizedObject] {
    def compare(that: SizedObject) = size.compareTo(that.size)
  }

  def biggestBlobs(implicit objectDB: ObjectDirectory, progressMonitor: ProgressMonitor = NullProgressMonitor.INSTANCE): Stream[SizedObject] = {
    val reader = objectDB.newReader
    objectDB.getPacks.flatMap {
      pack =>
        pack.map(_.toObjectId).map {
          objectId =>
            progressMonitor update 1
            SizedObject(objectId, reader.getObjectSize(objectId, OBJ_ANY))
        }
    }.toSeq.sorted.reverse.toStream.filter(oid => reader.open(oid.objectId).getType == OBJ_BLOB)
  }
}
