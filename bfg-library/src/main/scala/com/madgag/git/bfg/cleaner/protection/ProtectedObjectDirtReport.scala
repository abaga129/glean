/*
 * Copyright (c) 2012, 2013 Roberto Tyley
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

package com.madgag.git.bfg.cleaner.protection

import org.eclipse.jgit.revwalk.RevObject
import org.eclipse.jgit.lib.ObjectId


/**
 * @param revObject - the protected object (eg protected because it is the HEAD commit)
 * @param originalTreeOrBlob - the unmodified content-object referred to by the protected object (may be same object)
 * @param replacementTreeOrBlob - an option, populated if cleaning creates a replacement for the content-object
 */
case class ProtectedObjectDirtReport(revObject: RevObject, originalTreeOrBlob: RevObject, replacementTreeOrBlob: Option[ObjectId]) {
  val dirtProtector: Boolean = replacementTreeOrBlob.isDefined
}
