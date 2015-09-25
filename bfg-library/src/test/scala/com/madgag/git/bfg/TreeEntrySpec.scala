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

import com.madgag.git.bfg.model.{FileName, Tree}
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.FileMode._
import org.eclipse.jgit.lib.ObjectId.zeroId
import org.specs2.mutable._

class TreeEntrySpec extends Specification {

  def a(mode: FileMode, name: String) = Tree.Entry(FileName(name), mode, zeroId)

  "Tree entry ordering" should {
    "match ordering used by Git" in {
      a(TREE, "agit-test-utils") should be < a(TREE, "agit")
    }
  }
}