/*
 * Copyright ©1998-2020 by Richard A. Wilkes. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, version 2.0. If a copy of the MPL was not distributed with
 * this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, version 2.0.
 */

package com.trollworks.gcs.library;

import com.trollworks.gcs.datafile.DataFileDockable;
import com.trollworks.gcs.menu.StdMenuBar;
import com.trollworks.gcs.preferences.Preferences;
import com.trollworks.gcs.ui.border.EmptyBorder;
import com.trollworks.gcs.ui.border.LineBorder;
import com.trollworks.gcs.ui.widget.WindowUtils;
import com.trollworks.gcs.ui.widget.Workspace;
import com.trollworks.gcs.ui.widget.dock.Dockable;
import com.trollworks.gcs.utility.I18n;
import com.trollworks.gcs.utility.Log;
import com.trollworks.gcs.utility.RecursiveDirectoryRemover;
import com.trollworks.gcs.utility.UpdateChecker;
import com.trollworks.gcs.utility.UrlUtils;
import com.trollworks.gcs.utility.Version;
import com.trollworks.gcs.utility.json.Json;
import com.trollworks.gcs.utility.json.JsonArray;
import com.trollworks.gcs.utility.json.JsonMap;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;

public class Library implements Runnable {
    private static final String          SHA_PREFIX   = "\"sha\": \"";
    private static final String          SHA_SUFFIX   = "\",";
    private static final String          ROOT_PREFIX  = "richardwilkes-gcs_library-";
    private static final String          VERSION_FILE = "version.txt";
    private static final ExecutorService QUEUE        = Executors.newSingleThreadExecutor();
    private              String          mResult;
    private              JDialog         mDialog;
    private              boolean         mUpdateComplete;

    public static final String getRecordedCommit() {
        Path path = Preferences.getInstance().getMasterLibraryPath().resolve(VERSION_FILE);
        if (Files.exists(path)) {
            try (BufferedReader in = Files.newBufferedReader(path)) {
                String line = in.readLine();
                while (line != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        return line;
                    }
                    line = in.readLine();
                }
            } catch (IOException exception) {
                Log.warn(exception);
            }
        }
        return "";
    }

    public static final String getLatestCommit() {
        String sha = "";
        try {
            JsonArray array = Json.asArray(Json.parse(new URL("https://api.github.com/repos/richardwilkes/gcs_library/commits?per_page=1")), false);
            JsonMap   map   = array.getMap(0, false);
            sha = map.getString("sha", false);
            if (sha.length() > 7) {
                sha = sha.substring(0, 7);
            }
        } catch (IOException exception) {
            Log.error(exception);
        }
        return sha;
    }

    public static final long getMinimumGCSVersion() {
        String version = "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(UrlUtils.setupConnection("https://raw.githubusercontent.com/richardwilkes/gcs_library/master/minimum_version.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (version.isBlank()) {
                    line = line.trim();
                    if (!line.isBlank()) {
                        version = line;
                    }
                }
            }
        } catch (IOException exception) {
            Log.error(exception);
        }
        return Version.extract(version, 0);
    }

    public static List<Object> collectFiles() {
        String masterText = I18n.Text("Master Library");
        String userText   = I18n.Text("User Library");
        FutureTask<List<Object>> task = new FutureTask<>(() -> {
            Set<Path>    dirs = new HashSet<>();
            List<Object> list = new ArrayList<>();
            list.add("GCS");
            Preferences prefs = Preferences.getInstance();
            list.add(LibraryCollector.list(masterText, prefs.getMasterLibraryPath(), dirs));
            list.add(LibraryCollector.list(userText, prefs.getUserLibraryPath(), dirs));
            LibraryWatcher.INSTANCE.watchDirs(dirs);
            return list;
        });
        QUEUE.submit(task);
        try {
            return task.get();
        } catch (Exception exception) {
            Log.error(exception);
            List<Object> list   = new ArrayList<>();
            List<Object> master = new ArrayList<>();
            List<Object> user   = new ArrayList<>();
            master.add(masterText);
            list.add(master);
            user.add(userText);
            list.add(user);
            return list;
        }
    }

    public static final void downloadIfNotPresent() {
        if (getRecordedCommit().isBlank()) {
            download();
        }
    }

    public static final void download() {
        Library lib = new Library();
        if (GraphicsEnvironment.isHeadless()) {
            FutureTask<Object> task = new FutureTask<>(lib, null);
            QUEUE.submit(task);
            try {
                task.get();
            } catch (Exception exception) {
                Log.error(exception);
            }
        } else {
            // Close any open files that come from the master library
            Workspace workspace = Workspace.get();
            Path      prefix    = Preferences.getInstance().getMasterLibraryPath();
            for (Dockable dockable : workspace.getDock().getDockables()) {
                if (dockable instanceof DataFileDockable) {
                    DataFileDockable dfd  = (DataFileDockable) dockable;
                    Path             path = dfd.getBackingFile();
                    if (path != null && path.toAbsolutePath().startsWith(prefix)) {
                        if (dfd.mayAttemptClose()) {
                            if (!dfd.attemptClose()) {
                                JOptionPane.showMessageDialog(null, I18n.Text("GCS Master Library update was canceled."), I18n.Text("Canceled!"), JOptionPane.INFORMATION_MESSAGE);
                                return;
                            }
                        }
                    }
                }
            }

            // Put up a progress dialog
            JDialog dialog = new JDialog(workspace, I18n.Text("Update Master Library"), true);
            dialog.setResizable(false);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.setUndecorated(true);
            JComponent content = (JComponent) dialog.getContentPane();
            content.setLayout(new BorderLayout());
            content.setBorder(new CompoundBorder(new LineBorder(), new EmptyBorder(10)));
            content.add(new JLabel(I18n.Text("Downloading and installing the Master Library…")), BorderLayout.NORTH);
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            content.add(bar);
            dialog.pack();
            dialog.setLocationRelativeTo(workspace);
            StdMenuBar.SUPRESS_MENUS = true;
            lib.mDialog = dialog;
            QUEUE.submit(lib);
            dialog.setVisible(true);
        }
    }

    private Library() {
    }

    @Override
    public void run() {
        if (mUpdateComplete) {
            doCleanup();
        } else {
            doDownload();
        }
    }

    private void doDownload() {
        try {
            Preferences prefs = Preferences.getInstance();
            LibraryWatcher.INSTANCE.watchDirs(new HashSet<>());
            Path    root           = prefs.getMasterLibraryPath();
            boolean shouldContinue = true;
            Path    saveRoot       = root.resolveSibling(root.getFileName().toString() + ".save");
            if (Files.exists(root)) {
                try {
                    Files.move(root, saveRoot);
                } catch (IOException exception) {
                    shouldContinue = false;
                    Log.error(exception);
                    mResult = exception.getMessage();
                    if (mResult == null) {
                        mResult = "exception";
                    }
                }
            }
            if (shouldContinue) {
                prefs.getMasterLibraryPath(); // will recreate the dir
                try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(UrlUtils.setupConnection("https://api.github.com/repos/richardwilkes/gcs_library/zipball/master").getInputStream()))) {
                    byte[]   buffer = new byte[8192];
                    ZipEntry entry;
                    String   sha    = "unknown";
                    while ((entry = in.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        Path entryPath = Paths.get(entry.getName());
                        int  nameCount = entryPath.getNameCount();
                        if (nameCount < 3 || !entryPath.getName(0).toString().startsWith(ROOT_PREFIX) || !"Library".equals(entryPath.getName(1).toString())) {
                            continue;
                        }
                        long size = entry.getSize();
                        if (size < 1) {
                            continue;
                        }
                        sha = entryPath.getName(0).toString().substring(ROOT_PREFIX.length());
                        entryPath = entryPath.subpath(2, nameCount);
                        Path path = root.resolve(entryPath);
                        Files.createDirectories(path.getParent());
                        try (OutputStream out = Files.newOutputStream(path)) {
                            while (size > 0) {
                                int amt = in.read(buffer);
                                if (amt < 0) {
                                    break;
                                }
                                if (amt > 0) {
                                    size -= amt;
                                    out.write(buffer, 0, amt);
                                }
                            }
                        }
                    }
                    if (sha.length() > 7) {
                        sha = sha.substring(0, 7);
                    }
                    Files.writeString(root.resolve(VERSION_FILE), sha + "\n");
                    UpdateChecker.setDataResult(I18n.Text("You have the most recent version of the Master Library"), false);
                } catch (IOException exception) {
                    Log.error(exception);
                    mResult = exception.getMessage();
                    if (mResult == null) {
                        mResult = "exception";
                    }
                }
                if (mResult == null) {
                    if (Files.exists(saveRoot)) {
                        RecursiveDirectoryRemover.remove(saveRoot, true);
                    }
                } else {
                    RecursiveDirectoryRemover.remove(root, true);
                    if (Files.exists(saveRoot)) {
                        try {
                            Files.move(saveRoot, root);
                        } catch (IOException exception) {
                            Log.error(exception);
                        }
                    } else {
                        prefs.getMasterLibraryPath(); // will recreate the dir
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.error(throwable);
            if (mResult == null) {
                mResult = throwable.getMessage();
                if (mResult == null) {
                    mResult = "exception";
                }
            }
        }
        mUpdateComplete = true;
        if (!GraphicsEnvironment.isHeadless()) {
            EventQueue.invokeLater(this);
        }
    }

    private void doCleanup() {
        // Refresh the library view and let the user know what happened
        LibraryExplorerDockable libraryDockable = LibraryExplorerDockable.get();
        if (libraryDockable != null) {
            libraryDockable.refresh();
        }
        mDialog.dispose();
        StdMenuBar.SUPRESS_MENUS = false;
        if (mResult == null) {
            JOptionPane.showMessageDialog(null, I18n.Text("GCS Master Library update was successful."), I18n.Text("Success!"), JOptionPane.INFORMATION_MESSAGE);
        } else {
            WindowUtils.showError(null, I18n.Text("An error occurred while trying to update the GCS Master Library:") + "\n\n" + mResult);
        }
    }
}
