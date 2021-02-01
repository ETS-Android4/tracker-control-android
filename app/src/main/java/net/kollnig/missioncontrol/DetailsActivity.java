/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.opencsv.CSVWriter;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PlayStore;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import eu.faircode.netguard.DatabaseHelper;

import static net.kollnig.missioncontrol.data.InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY;
import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;
import static net.kollnig.missioncontrol.data.TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY;

public class DetailsActivity extends AppCompatActivity {
    public static final String INTENT_EXTRA_APP_PACKAGENAME = "INTENT_APP_PACKAGENAME";
    public static final String INTENT_EXTRA_APP_UID = "INTENT_APP_UID";
    public static final String INTENT_EXTRA_APP_NAME = "INTENT_APP_NAME";

    public static final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;
    public static PlayStore.AppInfo app = null;
    private final String TAG = DetailsActivity.class.getSimpleName();
    final File exportDir = new File(
            Environment.getExternalStorageDirectory(), "trackercontrol");
    private Integer appUid;
    private String appPackageName;

    private static Set<String> intToStringSet(Set<Integer> ints) {
        Set<String> strings = new HashSet<>();


        for (Integer _int : ints) {
            strings.add(String.valueOf(_int));
        }

        return strings;
    }

    public static void savePrefs(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();

        // Tracker settings
        TrackerBlocklist b = TrackerBlocklist.getInstance(c);
        Set<Integer> trackerIntSet = b.getBlocklist();
        Set<String> trackerSet = intToStringSet(trackerIntSet);
        editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, trackerSet);
        for (Integer uid : trackerIntSet) {
            Set<String> subset = b.getSubset(uid);
            editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + uid, subset);
        }

        // Internet settings
        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(c);
        Set<String> internetSet = intToStringSet(internetBlocklist.getBlocklist());
        editor.putStringSet(SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, internetSet);

        editor.apply();
    }

    private DetailsPagesAdapter detailsPagesAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_details);

        // Receive about details
        Intent intent = getIntent();
        appPackageName = intent.getStringExtra(INTENT_EXTRA_APP_PACKAGENAME);
        appUid = intent.getIntExtra(INTENT_EXTRA_APP_UID, -1);
        String appName = intent.getStringExtra(INTENT_EXTRA_APP_NAME);

        // Set up paging
        detailsPagesAdapter =
                new DetailsPagesAdapter(
                        this,
                        Common.getAppName(getPackageManager(), appUid),
                        appName,
                        appUid);
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        viewPager.setAdapter(detailsPagesAdapter);
        TabLayout tabs = findViewById(R.id.tabs);
        new TabLayoutMediator(tabs, viewPager, (tab, position) ->
                tab.setText(getResources().getString(DetailsPagesAdapter.TAB_TITLES[position]))
        ).attach();

        // set toolbar and back arrow
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Set title
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        toolbar.setTitle(getString(R.string.app_info));
        toolbar.setSubtitle(appName);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_details, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();// Respond to the action bar's Up/Home button
        if (itemId == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        } else if (itemId == R.id.action_export_csv) {
            if (hasPermissions()) {
                exportCsv();
            }
            return true;
        } else if (itemId == R.id.action_clear) {
            DatabaseHelper dh = DatabaseHelper.getInstance(this);
            dh.clearAccess(appUid, false);
            detailsPagesAdapter.updateTrackerLists();
            return true;
        } else if (itemId == R.id.action_launch) {
            Intent launch = Common.getLaunchIntent(this, appPackageName);
            if (launch != null)
                startActivity(launch);
            return true;
        } else if (itemId == R.id.action_uninstall) {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + appPackageName));
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        super.onPause();
        savePrefs(this);
    }

    public boolean hasPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)
            return true;

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
        return false;
    }

    public void exportCsv() {
        if (!exportDir.exists()) {
            try {
                if (!exportDir.mkdir())
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                return;
            } catch (SecurityException ecp) {
                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        new ExportDatabaseCSVTask().execute();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE) {// If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportCsv();
            } else {
                Toast.makeText(this, "Access to files required..", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void shareExport() {
        File sharingFile = new File(exportDir, appPackageName + ".csv");
        Uri uri = FileProvider.getUriForFile(Objects.requireNonNull(getApplicationContext()),
                BuildConfig.APPLICATION_ID + ".provider", sharingFile);

        Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setType("application/csv");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share CSV"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        app = null;
    }

    class ExportDatabaseCSVTask extends AsyncTask<String, Void, Boolean> {
        private final ProgressDialog dialog = new ProgressDialog(DetailsActivity.this);
        TrackerList trackerList;

        @Override
        protected void onPreExecute() {
            this.dialog.setMessage(getString(R.string.exporting));
            this.dialog.show();
            trackerList = TrackerList.getInstance(DetailsActivity.this);
        }

        protected Boolean doInBackground(final String... args) {
            if (exportDir == null) return false;

            File file = new File(exportDir, appPackageName + ".csv");
            try {
                file.createNewFile();
                CSVWriter csv = new CSVWriter(new FileWriter(file),
                        CSVWriter.DEFAULT_SEPARATOR,
                        CSVWriter.DEFAULT_QUOTE_CHARACTER,
                        CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                        CSVWriter.RFC4180_LINE_END);

                Cursor data = trackerList.getAppInfo(appUid);
                if (data == null) return false;

                List<String> columnNames = new ArrayList<>();
                Collections.addAll(columnNames, data.getColumnNames());
                columnNames.add("Tracker Name");
                columnNames.add("Tracker Category");

                csv.writeNext(columnNames.toArray(new String[0]));
                while (data.moveToNext()) {
                    String[] row = new String[data.getColumnNames().length + 2];
                    for (int i = 0; i < data.getColumnNames().length; i++) {
                        row[i] = data.getString(i);
                    }

                    String hostname = data.getString(data.getColumnIndex("daddr"));
                    Tracker tracker = TrackerList.findTracker(hostname);
                    if (tracker != null) {
                        row[data.getColumnNames().length] = tracker.getName();
                        row[data.getColumnNames().length + 1] = tracker.getCategory();
                    } else {
                        row[data.getColumnNames().length] = "";
                        row[data.getColumnNames().length + 1] = "";
                    }

                    csv.writeNext(row);
                }
                csv.close();
                data.close();
            } catch (IOException e) {
                return false;
            }

            return true;
        }

        protected void onPostExecute(final Boolean success) {
            if (this.dialog.isShowing()) {
                this.dialog.dismiss();
            }

            if (!success) {
                Toast.makeText(DetailsActivity.this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            // Export successful, ask user to further share file!
            View v = findViewById(R.id.view_pager);
            Snackbar s = Snackbar.make(v, R.string.exported, Snackbar.LENGTH_LONG);
            s.setAction(R.string.share_csv, v1 -> shareExport());
            s.setActionTextColor(getResources().getColor(R.color.colorPrimary));
            s.show();
        }
    }
}