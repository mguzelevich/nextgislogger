/*
 * *****************************************************************************
 * Project: NextGIS Logger
 * Purpose: Productive data logger for Android
 * Author:  Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright © 2015-2016 NextGIS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * *****************************************************************************
 */

package com.nextgis.logger;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.nextgis.logger.UI.ProgressBarActivity;
import com.nextgis.logger.engines.ArduinoEngine;
import com.nextgis.logger.engines.BaseEngine;
import com.nextgis.logger.engines.CellEngine;
import com.nextgis.logger.engines.SensorEngine;
import com.nextgis.logger.util.FileUtil;
import com.nextgis.logger.util.LoggerConstants;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.MapUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SessionsActivity extends ProgressBarActivity implements View.OnClickListener {
    private static final int SHARE = 1;
    private static int LAYOUT = android.R.layout.simple_list_item_multiple_choice;

    public static final int TYPE_MSTDT = 0; // marks and service together, data together
    public static final int TYPE_MSTDS = 1; // marks and service together, data separated
    public static final int TYPE_MSSDT = 2; // marks and service separated, data together
    public static final int TYPE_MSSDS = 3; // marks and service separated, data separated

    private ListView mLvSessions;
    private List<Feature> mSessions;
    private List<String> mSessionsName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sessions_activity);

        mSessionsName = new ArrayList<>();
        mSessions = new ArrayList<>();

        loadSessions();

        mLvSessions = (ListView) findViewById(R.id.lv_sessions);
        mLvSessions.setAdapter(new ArrayAdapter<>(this, LAYOUT, mSessionsName));

        if (mFAB != null)
            mFAB.attachToListView(mLvSessions);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.sessions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        if (item.getItemId() == R.id.action_share || item.getItemId() == R.id.action_delete) {
            ArrayList<Integer> result = new ArrayList<>();
            SparseBooleanArray sbaSelectedItems = mLvSessions.getCheckedItemPositions();

            for (int i = 0; i < sbaSelectedItems.size(); i++) {
                if (sbaSelectedItems.valueAt(i)) {
                    result.add(i);
                }
            }

            if (result.size() > 0) {
                String[] ids = getIdsFromPositions(result);

                switch (item.getItemId()) {
                    case R.id.action_share:
                        shareSessions(ids);
                        return true;
                    case R.id.action_delete:
                        deleteSessions(ids, true);
                        return true;
                }
            } else
                Toast.makeText(this, R.string.sessions_nothing_selected, Toast.LENGTH_SHORT).show();
        }

        switch (item.getItemId()) {
            case R.id.action_select_all:
                for (int i = 0; i < mLvSessions.getAdapter().getCount(); i++)
                    mLvSessions.setItemChecked(i, !item.isChecked());

                item.setChecked(!item.isChecked());
                return true;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void loadSessions() {
        mSessionsName.clear();
        mSessions.clear();
        NGWVectorLayer sessionLayer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_SESSION);
        if (sessionLayer != null) {
            List<Long> ids = sessionLayer.query(null);
            for (Long id : ids) {
                Feature feature = sessionLayer.getFeature(id);
                if (feature == null)
                    continue;

                boolean isCurrentSession = mSessionId != null && mSessionId.equals(feature.getFieldValueAsString(LoggerApplication.FIELD_UNIQUE_ID));
                mSessions.add(feature);
                String name = feature.getFieldValueAsString(LoggerApplication.FIELD_NAME);
                mSessionsName.add(isCurrentSession ? name + " *" + getString(R.string.scl_current_session) + "*" : name);
            }

            Collections.sort(mSessionsName, Collections.reverseOrder());
        }
    }

    private void shareSessions(String[] ids) {
        ArrayList<Uri> logsZips = new ArrayList<>();

        try {
            File temp = new File(getExternalFilesDir(null), LoggerConstants.TEMP_PATH);
            boolean directoryExists = FileUtil.checkOrCreateDirectory(temp);
            if (!directoryExists)
                throw new IOException();

            String in = " IN (" + MapUtil.makePlaceholders(ids.length) + ")";
            SQLiteDatabase db = ((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false);
            Cursor sessions = db.query(LoggerApplication.TABLE_SESSION,
                                       new String[]{LoggerApplication.FIELD_UNIQUE_ID, LoggerApplication.FIELD_NAME, LoggerApplication.FIELD_USER,
                                                    LoggerApplication.FIELD_DEVICE_INFO}, LoggerApplication.FIELD_UNIQUE_ID + in, ids, null, null, null);

            if (sessions == null)
                return;

            if (sessions.moveToFirst()) {
                do {
                    Cursor marks = db.query(LoggerApplication.TABLE_MARK,
                                            new String[]{LoggerApplication.FIELD_UNIQUE_ID, LoggerApplication.FIELD_MARK_ID, LoggerApplication.FIELD_NAME,
                                                         LoggerApplication.FIELD_TIMESTAMP}, LoggerApplication.FIELD_SESSION + " = ?",
                                            new String[]{sessions.getString(0)}, null, null, LoggerApplication.FIELD_TIMESTAMP);

                    if (marks == null) // skip empty sessions
                        continue;

                    File path = new File(temp, sessions.getString(1));
                    directoryExists = FileUtil.checkOrCreateDirectory(path);
                    if (!directoryExists)
                        throw new IOException();

                    if (marks.moveToFirst()) {
                        do {
                            String preamble = BaseEngine.getPreamble(marks.getString(1), marks.getString(2), sessions.getString(2), marks.getLong(3));
                            preamble += LoggerConstants.CSV_SEPARATOR;
                            int savingType = 1;
                            switch (savingType) {
                                case TYPE_MSTDT:
                                    break;
                                case TYPE_MSTDS:
                                    writeMSTDS(db, path, preamble, marks.getString(0));
                                    break;
                                case TYPE_MSSDT:
                                    break;
                                case TYPE_MSSDS:
                                    break;
                                default:
                                    throw new RuntimeException("Type" + savingType + " is not supported.");
                            }
                        } while (marks.moveToNext());
                    }

                    marks.close();

                    File deviceInfoFile = new File(path, LoggerConstants.DEVICE_INFO);
                    FileUtil.append(deviceInfoFile.getAbsolutePath(), "\r\n\r\n", sessions.getString(3));

                    if (putToZip(path))
                        logsZips.add(Uri.fromFile(new File(temp, sessions.getString(1) + LoggerConstants.ZIP_EXT))); // add file's uri to share list
                } while (sessions.moveToNext());
            }

            sessions.close();
        } catch (SQLiteException | IOException e) {
            Toast.makeText(this, R.string.fs_error_msg, Toast.LENGTH_SHORT).show();
        }

        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE); // multiple sharing
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, logsZips); // set data
        shareIntent.setType("application/zip"); //set mime type
        startActivityForResult(Intent.createChooser(shareIntent, getString(R.string.share_sessions_title)), SHARE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SHARE:
                File temp = new File(getExternalFilesDir(null), LoggerConstants.TEMP_PATH);
                FileUtil.deleteDirectoryOrFile(temp);
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void writeMSTDS(SQLiteDatabase db, File path, String preamble, String markId) throws FileNotFoundException {
        Cursor data = db.query(LoggerApplication.TABLE_CELL, null, LoggerApplication.FIELD_MARK + " = ?", new String[]{markId}, null, null,
                               LoggerConstants.HEADER_ACTIVE);

        String header = LoggerConstants.CSV_HEADER_PREAMBLE + LoggerConstants.CSV_SEPARATOR;
        if (data != null) {
            if (data.moveToFirst()) {
                List<String> items = new ArrayList<>();
                do {
                    items.add(preamble + CellEngine.getDataFromCursor(data));
                } while (data.moveToNext());

                String filePath = new File(path, LoggerConstants.CELL + LoggerConstants.CSV_EXT).getAbsolutePath();
                FileUtil.append(filePath, header + CellEngine.getHeader(), items);
            }
            data.close();
        }

        data = db.query(LoggerApplication.TABLE_SENSOR, null, LoggerApplication.FIELD_MARK + " = ?", new String[]{markId}, null, null, null);
        if (data != null) {
            if (data.moveToFirst()) {
                String filePath = new File(path, LoggerConstants.SENSOR + LoggerConstants.CSV_EXT).getAbsolutePath();
                String item = preamble + SensorEngine.getDataFromCursor(data);
                FileUtil.append(filePath, header + SensorEngine.getHeader(), item);
            }
            data.close();
        }

        data = db.query(LoggerApplication.TABLE_EXTERNAL, null, LoggerApplication.FIELD_MARK + " = ?", new String[]{markId}, null, null, null);
        if (data != null) {
            if (data.moveToFirst()) {
                String filePath = new File(path, LoggerConstants.EXTERNAL + LoggerConstants.CSV_EXT).getAbsolutePath();
                String item = preamble + ArduinoEngine.getDataFromCursor(data);
                FileUtil.append(filePath, header + "data", item);
            }
            data.close();
        }
    }

    private boolean putToZip(File files) throws IOException {
        byte[] buffer = new byte[1024];
        if (!files.exists() || !files.isDirectory())
            return false;

        FileOutputStream fos = new FileOutputStream(files.getAbsolutePath() + LoggerConstants.ZIP_EXT);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

        for (File file : files.listFiles()) { // for each log-file in directory
            FileInputStream fis = new FileInputStream(file);
            zos.putNextEntry(new ZipEntry(file.getName())); // put it in zip

            int length;
            while ((length = fis.read(buffer)) > 0)
                // write it to zip
                zos.write(buffer, 0, length);

            zos.closeEntry();
            fis.close();
        }

        zos.close();
        return files.listFiles().length > 0;
    }

    private boolean deleteSessions(String[] ids, boolean ask) {
        boolean result = false;
        String authority = ((LoggerApplication) getApplication()).getAuthority();
        Uri uri;

        try {
            if (hasCurrentSession(ids)) {
                if (ask) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    final String[] finalIds = ids;
                    builder.setTitle(R.string.delete_sessions_title).setMessage(R.string.sessions_delete_current)
                           .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            deleteSessions(finalIds, false);
                        }
                    }).show();
                    return false;
                } else {
                    stopLoggerService();
                    clearSession();
                }
            }

            NGWVectorLayer layer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_MARK);
            String in = " IN (" + MapUtil.makePlaceholders(ids.length) + ")";
            List<String> markIds = new ArrayList<>();
            if (layer != null) {
                Cursor allMarks = layer.query(new String[]{LoggerApplication.FIELD_UNIQUE_ID}, LoggerApplication.FIELD_SESSION + in, ids, null, null);
                if (allMarks != null) {
                    if (allMarks.moveToFirst()) {
                        do {
                            markIds.add(allMarks.getString(0));
                        } while (allMarks.moveToNext());
                    }

                    allMarks.close();
                }

                uri = Uri.parse("content://" + authority + "/" + layer.getPath().getName() + "/");
                layer.delete(uri, LoggerApplication.FIELD_SESSION + in, ids);
                layer.rebuildCache(null);
            }

            layer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_SESSION);
            if (layer != null) {
                uri = Uri.parse("content://" + authority + "/" + layer.getPath().getName() + "/");
                layer.delete(uri, LoggerApplication.FIELD_UNIQUE_ID + in, ids);
                layer.rebuildCache(null);
            }

            ids = markIds.toArray(new String[markIds.size()]);
            in = " IN (" + MapUtil.makePlaceholders(ids.length) + ")";
            layer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_CELL);
            if (layer != null) {
                uri = Uri.parse("content://" + authority + "/" + layer.getPath().getName() + "/");
                layer.delete(uri, LoggerApplication.FIELD_MARK + in, ids);
                layer.rebuildCache(null);
            }

            layer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_SENSOR);
            if (layer != null) {
                uri = Uri.parse("content://" + authority + "/" + layer.getPath().getName() + "/");
                layer.delete(uri, LoggerApplication.FIELD_MARK + in, ids);
                layer.rebuildCache(null);
            }

            layer = (NGWVectorLayer) MapBase.getInstance().getLayerByName(LoggerApplication.TABLE_EXTERNAL);
            if (layer != null) {
                uri = Uri.parse("content://" + authority + "/" + layer.getPath().getName() + "/");
                layer.delete(uri, LoggerApplication.FIELD_MARK + in, ids);
                layer.rebuildCache(null);
            }

            result = true;

            // shrink database
            SQLiteDatabase db = ((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false);
            db.execSQL("VACUUM");
        } catch (SQLiteException e) {
            e.printStackTrace();
        }

        Toast.makeText(this, R.string.delete_sessions_done, Toast.LENGTH_SHORT).show();
        loadSessions();
        mLvSessions.setAdapter(new ArrayAdapter<>(this, LAYOUT, mSessionsName));

        return result;
    }

    private boolean hasCurrentSession(String[] ids) {
        for (String id : ids)
            if (id.equals(mSessionId))
                return true;

        return false;
    }

    private String[] getIdsFromPositions(List<Integer> positions) {
        String[] result = new String[positions.size()];
        for (int i = 0; i < positions.size(); i++)
            result[i] = mSessions.get(mSessions.size() - i - 1).getFieldValueAsString(LoggerApplication.FIELD_UNIQUE_ID);

        return result;
    }
}
