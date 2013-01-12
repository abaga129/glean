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

package com.madgag.git.bfg.cleaner

import org.scalatest._
import matchers.ShouldMatchers
import com.madgag.git.bfg.GitUtil._
import org.eclipse.jgit.lib.ObjectId
import com.madgag.git.bfg._

class ObjectIdSubstitutorSpec extends FlatSpec with ShouldMatchers {

  "Object Id Substitutor regex" should "match hex strings" in {

    "01234567890" should include regex (ObjectIdSubstitutor.hexRegex)

    "decade2001" should include regex (ObjectIdSubstitutor.hexRegex)

    "This is decade2001" should include regex (ObjectIdSubstitutor.hexRegex)

    "This is decade2001 I say" should include regex (ObjectIdSubstitutor.hexRegex)

    "This is Gdecade2001 I say" should not include regex (ObjectIdSubstitutor.hexRegex)

    "This is decade2001X I say" should not include regex (ObjectIdSubstitutor.hexRegex)
  }

  "Object Id" should "be substituted in commit message" in {
    implicit val repo = unpackRepo("/sample-repos/example.git.zip")
    implicit val reader = repo.newObjectReader

    val cleanedMessage = ObjectIdSubstitutor.replaceOldCommitIds("See 3699910d2baab1 for backstory", reader, new CleaningMapper[ObjectId] {
      val clean = (_: ObjectId) => abbrId("06d7405020018d")
    })

    cleanedMessage should be ("See 06d7405020018d [formerly 3699910d2baab1] for backstory")
  }

}