/*
 * *****************************************************************************
 * Project: NextGIS Logger
 * Purpose: Productive data logger for Android
 * Author:  Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright © 2016 NextGIS
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

package com.nextgis.logger.util;

import android.content.Context;
import android.content.SyncResult;

import com.nextgis.maplib.map.NGWVectorLayer;

import java.io.File;

public class LoggerVectorLayer extends NGWVectorLayer {
    public LoggerVectorLayer(Context context, File path) {
        super(context, path);
    }

    @Override
    public boolean getChangesFromServer(String authority, SyncResult syncResult) {
        return true;
    }
}
