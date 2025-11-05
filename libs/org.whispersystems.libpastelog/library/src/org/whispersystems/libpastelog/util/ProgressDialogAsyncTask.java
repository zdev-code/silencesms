/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.whispersystems.libpastelog.util;

import android.content.Context;

/**
 * Deprecated placeholder retained for binary compatibility.
 *
 * <p>No-op implementation that signals callers to migrate to
 * executor-based helpers. All usage should be replaced and this
 * class will be removed in a future release.</p>
 */
@Deprecated
public abstract class ProgressDialogAsyncTask<Params, Progress, Result> {

  protected ProgressDialogAsyncTask(Context context, String title, String message) {
    throw new UnsupportedOperationException("ProgressDialogAsyncTask has been removed; migrate to executor-based helpers.");
  }

  protected ProgressDialogAsyncTask(Context context, int title, int message) {
    this(context, context.getString(title), context.getString(message));
  }

  @SafeVarargs
  public final void execute(Params... params) {
    throw new UnsupportedOperationException("ProgressDialogAsyncTask has been removed; migrate to executor-based helpers.");
  }
}

