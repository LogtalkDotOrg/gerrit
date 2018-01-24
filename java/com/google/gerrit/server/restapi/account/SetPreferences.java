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

package com.google.gerrit.server.restapi.account;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AccountResource;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.AccountsUpdate;
import com.google.gerrit.server.account.Preferences;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class SetPreferences implements RestModifyView<AccountResource, GeneralPreferencesInfo> {
  private final Provider<CurrentUser> self;
  private final PermissionBackend permissionBackend;
  private final AccountsUpdate.User accountsUpdate;
  private final DynamicMap<DownloadScheme> downloadSchemes;

  @Inject
  SetPreferences(
      Provider<CurrentUser> self,
      PermissionBackend permissionBackend,
      AccountsUpdate.User accountsUpdate,
      DynamicMap<DownloadScheme> downloadSchemes) {
    this.self = self;
    this.permissionBackend = permissionBackend;
    this.accountsUpdate = accountsUpdate;
    this.downloadSchemes = downloadSchemes;
  }

  @Override
  public GeneralPreferencesInfo apply(AccountResource rsrc, GeneralPreferencesInfo input)
      throws RestApiException, IOException, ConfigInvalidException, PermissionBackendException,
          OrmException {
    if (self.get() != rsrc.getUser()) {
      permissionBackend.user(self).check(GlobalPermission.MODIFY_ACCOUNT);
    }

    checkDownloadScheme(input.downloadScheme);
    Preferences.validateMy(input.my);
    Account.Id id = rsrc.getUser().getAccountId();

    return accountsUpdate
        .create()
        .update("Set General Preferences via API", id, u -> u.setGeneralPreferences(input))
        .map(AccountState::getGeneralPreferences)
        .orElseThrow(() -> new ResourceNotFoundException(IdString.fromDecoded(id.toString())));
  }

  private void checkDownloadScheme(String downloadScheme) throws BadRequestException {
    if (Strings.isNullOrEmpty(downloadScheme)) {
      return;
    }

    for (DynamicMap.Entry<DownloadScheme> e : downloadSchemes) {
      if (e.getExportName().equals(downloadScheme) && e.getProvider().get().isEnabled()) {
        return;
      }
    }
    throw new BadRequestException("Unsupported download scheme: " + downloadScheme);
  }
}