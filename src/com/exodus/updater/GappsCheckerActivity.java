/*
 * Copyright 2014 Exodus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exodus.updater;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

/*
 * Activity to show alert dialogs on keyguard
*/
public class GappsCheckerActivity extends Activity {
    private static final String GMS_CORE = "com.google.android.gms";

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!areGappsInstalled()) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            final PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.FULL_WAKE_LOCK, "com.exodus.updater");
            wl.acquire();

            Toast.makeText(GappsCheckerActivity.this,
                    GappsCheckerActivity.this.getString(R.string.gapps_not_installed), Toast.LENGTH_LONG).show();
            finish();
        } else {
            finish();
        }
    }

    private boolean areGappsInstalled() {
        PackageManager pm = this.getPackageManager();
        try {
            pm.getPackageInfo(GMS_CORE, PackageManager.GET_ACTIVITIES);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }
}
