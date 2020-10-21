/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.wm.DisplayWindowSettings.SettingsProvider;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of {@link SettingsProvider} that reads the base settings provided in a display
 * settings file stored in /vendor/etc and then overlays those values with the settings provided in
 * /data/system.
 *
 * @see DisplayWindowSettings
 */
class DisplayWindowSettingsProvider implements SettingsProvider {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "DisplayWindowSettingsProvider" : TAG_WM;

    private static final String DATA_DISPLAY_SETTINGS_FILE_PATH = "system/display_settings.xml";
    private static final String VENDOR_DISPLAY_SETTINGS_PATH = "etc/display_settings.xml";
    private static final String WM_DISPLAY_COMMIT_TAG = "wm-displays";

    private static final int IDENTIFIER_UNIQUE_ID = 0;
    private static final int IDENTIFIER_PORT = 1;
    @IntDef(prefix = { "IDENTIFIER_" }, value = {
            IDENTIFIER_UNIQUE_ID,
            IDENTIFIER_PORT,
    })
    @interface DisplayIdentifierType {}

    /** Interface that allows reading the display window settings. */
    interface ReadableSettingsStorage {
        InputStream openRead() throws IOException;
    }

    /** Interface that allows reading and writing the display window settings. */
    interface WritableSettingsStorage extends ReadableSettingsStorage {
        OutputStream startWrite() throws IOException;
        void finishWrite(OutputStream os, boolean success);
    }

    private final ReadableSettingsStorage mBaseSettingsStorage;
    /**
     * The preferred type of a display identifier to use when storing and retrieving entries from
     * the base (vendor) settings file.
     *
     * @see #getIdentifier(DisplayInfo, int)
     */
    @DisplayIdentifierType
    private int mBaseIdentifierType;
    private final Map<String, SettingsEntry> mBaseSettings = new HashMap<>();

    private final WritableSettingsStorage mOverrideSettingsStorage;
    /**
     * The preferred type of a display identifier to use when storing and retrieving entries from
     * the data (override) settings file.
     *
     * @see #getIdentifier(DisplayInfo, int)
     */
    @DisplayIdentifierType
    private int mOverrideIdentifierType;
    private final Map<String, SettingsEntry> mOverrideSettings = new HashMap<>();

    DisplayWindowSettingsProvider() {
        this(new AtomicFileStorage(getVendorSettingsFile()),
                new AtomicFileStorage(getOverrideSettingsFile()));
    }

    @VisibleForTesting
    DisplayWindowSettingsProvider(@NonNull ReadableSettingsStorage baseSettingsStorage,
            @NonNull WritableSettingsStorage overrideSettingsStorage) {
        mBaseSettingsStorage = baseSettingsStorage;
        mOverrideSettingsStorage = overrideSettingsStorage;
        readSettings();
    }

    @Override
    @NonNull
    public SettingsEntry getSettings(@NonNull DisplayInfo info) {
        SettingsEntry baseSettings = getBaseSettingsEntry(info);
        SettingsEntry overrideSettings = getOrCreateOverrideSettingsEntry(info);
        if (baseSettings == null) {
            return new SettingsEntry(overrideSettings);
        } else {
            SettingsEntry mergedSettings = new SettingsEntry(baseSettings);
            mergedSettings.updateFrom(overrideSettings);
            return mergedSettings;
        }
    }

    @Override
    @NonNull
    public SettingsEntry getOverrideSettings(@NonNull DisplayInfo info) {
        return new SettingsEntry(getOrCreateOverrideSettingsEntry(info));
    }

    @Override
    public void updateOverrideSettings(@NonNull DisplayInfo info,
            @NonNull SettingsEntry overrides) {
        final SettingsEntry overrideSettings = getOrCreateOverrideSettingsEntry(info);
        boolean changed = overrideSettings.setTo(overrides);
        if (changed) {
            writeOverrideSettings();
        }
    }

    @Nullable
    private SettingsEntry getBaseSettingsEntry(DisplayInfo info) {
        final String identifier = getIdentifier(info, mBaseIdentifierType);
        SettingsEntry settings;
        // Try to get corresponding settings using preferred identifier for the current config.
        if ((settings = mBaseSettings.get(identifier)) != null) {
            return settings;
        }
        // Else, fall back to the display name.
        if ((settings = mBaseSettings.get(info.name)) != null) {
            // Found an entry stored with old identifier.
            mBaseSettings.remove(info.name);
            mBaseSettings.put(identifier, settings);
            return settings;
        }
        return null;
    }

    @NonNull
    private SettingsEntry getOrCreateOverrideSettingsEntry(DisplayInfo info) {
        final String identifier = getIdentifier(info, mOverrideIdentifierType);
        SettingsEntry settings;
        // Try to get corresponding settings using preferred identifier for the current config.
        if ((settings = mOverrideSettings.get(identifier)) != null) {
            return settings;
        }
        // Else, fall back to the display name.
        if ((settings = mOverrideSettings.get(info.name)) != null) {
            // Found an entry stored with old identifier.
            mOverrideSettings.remove(info.name);
            mOverrideSettings.put(identifier, settings);
            writeOverrideSettings();
            return settings;
        }

        settings = new SettingsEntry();
        mOverrideSettings.put(identifier, settings);
        return settings;
    }

    private void readSettings() {
        FileData baseFileData = readSettings(mBaseSettingsStorage);
        if (baseFileData != null) {
            mBaseIdentifierType = baseFileData.mIdentifierType;
            mBaseSettings.putAll(baseFileData.mSettings);
        }

        FileData overrideFileData = readSettings(mOverrideSettingsStorage);
        if (overrideFileData != null) {
            mOverrideIdentifierType = overrideFileData.mIdentifierType;
            mOverrideSettings.putAll(overrideFileData.mSettings);
        }
    }

    private void writeOverrideSettings() {
        FileData fileData = new FileData();
        fileData.mIdentifierType = mOverrideIdentifierType;
        fileData.mSettings.putAll(mOverrideSettings);
        writeSettings(mOverrideSettingsStorage, fileData);
    }

    /** Gets the identifier of choice for the current config. */
    private static String getIdentifier(DisplayInfo displayInfo, @DisplayIdentifierType int type) {
        if (type == IDENTIFIER_PORT && displayInfo.address != null) {
            // Config suggests using port as identifier for physical displays.
            if (displayInfo.address instanceof DisplayAddress.Physical) {
                return "port:" + ((DisplayAddress.Physical) displayInfo.address).getPort();
            }
        }
        return displayInfo.uniqueId;
    }

    @NonNull
    private static AtomicFile getVendorSettingsFile() {
        final File vendorFile = new File(Environment.getVendorDirectory(),
                VENDOR_DISPLAY_SETTINGS_PATH);
        return new AtomicFile(vendorFile, WM_DISPLAY_COMMIT_TAG);
    }

    @NonNull
    private static AtomicFile getOverrideSettingsFile() {
        final File overrideSettingsFile = new File(Environment.getDataDirectory(),
                DATA_DISPLAY_SETTINGS_FILE_PATH);
        return new AtomicFile(overrideSettingsFile, WM_DISPLAY_COMMIT_TAG);
    }

    @Nullable
    private static FileData readSettings(ReadableSettingsStorage storage) {
        InputStream stream;
        try {
            stream = storage.openRead();
        } catch (IOException e) {
            Slog.i(TAG, "No existing display settings, starting empty");
            return null;
        }
        FileData fileData = new FileData();
        boolean success = false;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Do nothing.
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("display")) {
                    readDisplay(parser, fileData);
                } else if (tagName.equals("config")) {
                    readConfig(parser, fileData);
                } else {
                    Slog.w(TAG, "Unknown element under <display-settings>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            success = true;
        } catch (IllegalStateException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (IOException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        if (!success) {
            fileData.mSettings.clear();
        }
        return fileData;
    }

    private static int getIntAttribute(XmlPullParser parser, String name, int defaultValue) {
        try {
            final String str = parser.getAttributeValue(null, name);
            return str != null ? Integer.parseInt(str) : defaultValue;
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to parse display window settings attribute: " + name, e);
            return defaultValue;
        }
    }

    @Nullable
    private static Integer getIntegerAttribute(XmlPullParser parser, String name,
            @Nullable Integer defaultValue) {
        try {
            final String str = parser.getAttributeValue(null, name);
            return str != null ? Integer.valueOf(str) : defaultValue;
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed to parse display window settings attribute: " + name, e);
            return defaultValue;
        }
    }

    @Nullable
    private static Boolean getBooleanAttribute(XmlPullParser parser, String name,
            @Nullable Boolean defaultValue) {
        final String str = parser.getAttributeValue(null, name);
        return str != null ? Boolean.valueOf(str) : defaultValue;
    }

    private static void readDisplay(XmlPullParser parser, FileData fileData)
            throws NumberFormatException, XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        if (name != null) {
            SettingsEntry settingsEntry = new SettingsEntry();
            settingsEntry.mWindowingMode = getIntAttribute(parser, "windowingMode",
                    WindowConfiguration.WINDOWING_MODE_UNDEFINED /* defaultValue */);
            settingsEntry.mUserRotationMode = getIntegerAttribute(parser, "userRotationMode",
                    null /* defaultValue */);
            settingsEntry.mUserRotation = getIntegerAttribute(parser, "userRotation",
                    null /* defaultValue */);
            settingsEntry.mForcedWidth = getIntAttribute(parser, "forcedWidth",
                    0 /* defaultValue */);
            settingsEntry.mForcedHeight = getIntAttribute(parser, "forcedHeight",
                    0 /* defaultValue */);
            settingsEntry.mForcedDensity = getIntAttribute(parser, "forcedDensity",
                    0 /* defaultValue */);
            settingsEntry.mForcedScalingMode = getIntegerAttribute(parser, "forcedScalingMode",
                    null /* defaultValue */);
            settingsEntry.mRemoveContentMode = getIntAttribute(parser, "removeContentMode",
                    REMOVE_CONTENT_MODE_UNDEFINED /* defaultValue */);
            settingsEntry.mShouldShowWithInsecureKeyguard = getBooleanAttribute(parser,
                    "shouldShowWithInsecureKeyguard", null /* defaultValue */);
            settingsEntry.mShouldShowSystemDecors = getBooleanAttribute(parser,
                    "shouldShowSystemDecors", null /* defaultValue */);
            settingsEntry.mShouldShowIme = getBooleanAttribute(parser, "shouldShowIme",
                    null /* defaultValue */);
            settingsEntry.mFixedToUserRotation = getIntegerAttribute(parser, "fixedToUserRotation",
                    null /* defaultValue */);
            settingsEntry.mIgnoreOrientationRequest = getBooleanAttribute(parser,
                    "ignoreOrientationRequest", null /* defaultValue */);
            fileData.mSettings.put(name, settingsEntry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    private static void readConfig(XmlPullParser parser, FileData fileData)
            throws NumberFormatException,
            XmlPullParserException, IOException {
        fileData.mIdentifierType = getIntAttribute(parser, "identifier",
                IDENTIFIER_UNIQUE_ID);
        XmlUtils.skipCurrentTag(parser);
    }

    private static void writeSettings(WritableSettingsStorage storage, FileData data) {
        OutputStream stream;
        try {
            stream = storage.startWrite();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display settings: " + e);
            return;
        }

        boolean success = false;
        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            out.startTag(null, "display-settings");

            out.startTag(null, "config");
            out.attribute(null, "identifier",
                    Integer.toString(data.mIdentifierType));
            out.endTag(null, "config");

            for (Map.Entry<String, SettingsEntry> entry
                    : data.mSettings.entrySet()) {
                String displayIdentifier = entry.getKey();
                SettingsEntry settingsEntry = entry.getValue();
                if (settingsEntry.isEmpty()) {
                    continue;
                }

                out.startTag(null, "display");
                out.attribute(null, "name", displayIdentifier);
                if (settingsEntry.mWindowingMode != WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
                    out.attribute(null, "windowingMode",
                            Integer.toString(settingsEntry.mWindowingMode));
                }
                if (settingsEntry.mUserRotationMode != null) {
                    out.attribute(null, "userRotationMode",
                            settingsEntry.mUserRotationMode.toString());
                }
                if (settingsEntry.mUserRotation != null) {
                    out.attribute(null, "userRotation",
                            settingsEntry.mUserRotation.toString());
                }
                if (settingsEntry.mForcedWidth != 0 && settingsEntry.mForcedHeight != 0) {
                    out.attribute(null, "forcedWidth",
                            Integer.toString(settingsEntry.mForcedWidth));
                    out.attribute(null, "forcedHeight",
                            Integer.toString(settingsEntry.mForcedHeight));
                }
                if (settingsEntry.mForcedDensity != 0) {
                    out.attribute(null, "forcedDensity",
                            Integer.toString(settingsEntry.mForcedDensity));
                }
                if (settingsEntry.mForcedScalingMode != null) {
                    out.attribute(null, "forcedScalingMode",
                            settingsEntry.mForcedScalingMode.toString());
                }
                if (settingsEntry.mRemoveContentMode != REMOVE_CONTENT_MODE_UNDEFINED) {
                    out.attribute(null, "removeContentMode",
                            Integer.toString(settingsEntry.mRemoveContentMode));
                }
                if (settingsEntry.mShouldShowWithInsecureKeyguard != null) {
                    out.attribute(null, "shouldShowWithInsecureKeyguard",
                            settingsEntry.mShouldShowWithInsecureKeyguard.toString());
                }
                if (settingsEntry.mShouldShowSystemDecors != null) {
                    out.attribute(null, "shouldShowSystemDecors",
                            settingsEntry.mShouldShowSystemDecors.toString());
                }
                if (settingsEntry.mShouldShowIme != null) {
                    out.attribute(null, "shouldShowIme",
                            settingsEntry.mShouldShowIme.toString());
                }
                if (settingsEntry.mFixedToUserRotation != null) {
                    out.attribute(null, "fixedToUserRotation",
                            settingsEntry.mFixedToUserRotation.toString());
                }
                if (settingsEntry.mIgnoreOrientationRequest != null) {
                    out.attribute(null, "ignoreOrientationRequest",
                            settingsEntry.mIgnoreOrientationRequest.toString());
                }
                out.endTag(null, "display");
            }

            out.endTag(null, "display-settings");
            out.endDocument();
            success = true;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display window settings.", e);
        } finally {
            storage.finishWrite(stream, success);
        }
    }

    private static final class FileData {
        int mIdentifierType;
        final Map<String, SettingsEntry> mSettings = new HashMap<>();

        @Override
        public String toString() {
            return "FileData{"
                    + "mIdentifierType=" + mIdentifierType
                    + ", mSettings=" + mSettings
                    + '}';
        }
    }

    private static final class AtomicFileStorage implements WritableSettingsStorage {
        private final AtomicFile mAtomicFile;

        AtomicFileStorage(@NonNull AtomicFile atomicFile) {
            mAtomicFile = atomicFile;
        }

        @Override
        public InputStream openRead() throws FileNotFoundException {
            return mAtomicFile.openRead();
        }

        @Override
        public OutputStream startWrite() throws IOException {
            return mAtomicFile.startWrite();
        }

        @Override
        public void finishWrite(OutputStream os, boolean success) {
            if (!(os instanceof FileOutputStream)) {
                throw new IllegalArgumentException("Unexpected OutputStream as argument: " + os);
            }
            FileOutputStream fos = (FileOutputStream) os;
            if (success) {
                mAtomicFile.finishWrite(fos);
            } else {
                mAtomicFile.failWrite(fos);
            }
        }
    }
}
