// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.change;

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetInfo;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.project.RefControl;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.Collections;
import java.util.Set;

public class ChangeInserter {
  public static interface Factory {
    ChangeInserter create(RefControl ctl, Change c, PatchSet ps, RevCommit rc,
        PatchSetInfo psi);
  }

  private final Provider<ReviewDb> dbProvider;
  private final GitReferenceUpdated gitRefUpdated;
  private final ChangeHooks hooks;
  private final ApprovalsUtil approvalsUtil;
  private final TrackingFooters trackingFooters;

  private final RefControl refControl;
  private final Change change;
  private final PatchSet patchSet;
  private final RevCommit commit;
  private final PatchSetInfo patchSetInfo;

  private ChangeMessage changeMessage;
  private Set<Account.Id> reviewers;

  @Inject
  ChangeInserter(Provider<ReviewDb> dbProvider,
      GitReferenceUpdated gitRefUpdated,
      ChangeHooks hooks,
      ApprovalsUtil approvalsUtil,
      TrackingFooters trackingFooters,
      @Assisted RefControl refControl,
      @Assisted Change change,
      @Assisted PatchSet patchSet,
      @Assisted RevCommit commit,
      @Assisted PatchSetInfo patchSetInfo) {
    this.dbProvider = dbProvider;
    this.gitRefUpdated = gitRefUpdated;
    this.hooks = hooks;
    this.approvalsUtil = approvalsUtil;
    this.trackingFooters = trackingFooters;
    this.refControl = refControl;
    this.change = change;
    this.patchSet = patchSet;
    this.commit = commit;
    this.patchSetInfo = patchSetInfo;

    this.reviewers = Collections.emptySet();
  }

  public ChangeInserter setMessage(ChangeMessage changeMessage) {
    this.changeMessage = changeMessage;
    return this;
  }

  public ChangeInserter setReviewers(Set<Account.Id> reviewers) {
    this.reviewers = reviewers;
    return this;
  }

  public void insert() throws OrmException {
    ReviewDb db = dbProvider.get();
    db.changes().beginTransaction(change.getId());
    try {
      ChangeUtil.insertAncestors(db, patchSet.getId(), commit);
      db.patchSets().insert(Collections.singleton(patchSet));
      db.changes().insert(Collections.singleton(change));
      ChangeUtil.updateTrackingIds(db, change, trackingFooters, commit.getFooterLines());
      LabelTypes labelTypes = refControl.getProjectControl().getLabelTypes();
      approvalsUtil.addReviewers(db, labelTypes, change, patchSet, patchSetInfo,
          reviewers, Collections.<Account.Id> emptySet());
      db.commit();
    } finally {
      db.rollback();
    }
    if (changeMessage != null) {
      db.changeMessages().insert(Collections.singleton(changeMessage));
    }

    gitRefUpdated.fire(change.getProject(), patchSet.getRefName(),
        ObjectId.zeroId(), commit);
    hooks.doPatchsetCreatedHook(change, patchSet, db);
  }
}
