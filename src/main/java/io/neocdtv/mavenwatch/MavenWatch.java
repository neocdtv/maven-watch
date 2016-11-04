/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Oracle nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.neocdtv.mavenwatch;

import com.sun.nio.file.ExtendedWatchEventModifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;


/* Not working */
public class MavenWatch {

    private static Logger LOGGER = Logger.getLogger(MavenWatch.class.getClass().getName());
    private static String SUFFIX_CLASS = ".class";
    private static String SUFFIX_JAVA = ".java";
    private static String MAVEN_SRC_JAVA = "/src/main/java/";
    private static String MAVEN_TARGET_CLASSES = "/target/classes/";
    // TODO: find target/.*/WEB-INF
    //private static String MAVEN_TARGET_WEB_APP_CLASSES = "/target/ofco/WEB-INF/classes/";
    private static String OS = System.getProperty("os.name").toLowerCase();

    private WatchService watcher;
    private Map<WatchKey, Path> watchedDirectories;

    public static void main(String[] args) throws IOException {
        configureLogger(Level.FINE);
        LOGGER.log(Level.FINE, "args {0}", args);
        if (args.length == 0) {
            usage();
        }
        registerDirectoryAndProcessItsEvents(args[0]);
    }

    public static void registerDirectoryAndProcessItsEvents(String dirToWatch) throws IOException {
        Path dir = Paths.get(dirToWatch);
        new MavenWatch(dir).processEvents();
    }

    static <T> WatchEvent<T> castEvent(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    private void registerDirectoryToBeWatched(Path directoryToWatch) throws IOException {
        WatchKey watchedDirectory;
        if (isWindows()) {
            WatchEvent.Modifier[] modifiers = new WatchEvent.Modifier[1];
            modifiers[0] = ExtendedWatchEventModifier.FILE_TREE;
            watchedDirectory = directoryToWatch.register(watcher, new WatchEvent.Kind<?>[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY}, modifiers);
        } else {
            watchedDirectory = directoryToWatch.register(watcher, new WatchEvent.Kind<?>[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY});
        }
        logDirectoryRegistration(watchedDirectory, directoryToWatch);
        watchedDirectories.put(watchedDirectory, directoryToWatch);
    }

    private void registerDirectoryAndSubDirectoriesToBeWatched(Path rootDirectory) throws IOException {
        Files.walkFileTree(rootDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                registerDirectoryToBeWatched(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void logDirectoryRegistration(WatchKey watchedDirectory, Path dirToWatch) {
        Path prev = watchedDirectories.get(watchedDirectory);
        if (prev == null) {
            LOGGER.log(Level.FINE, "REGISTER: {0}");
        } else if (!dirToWatch.equals(prev)) {
            LOGGER.log(Level.FINE, "UPDATE: {0}", Arrays.asList(prev, dirToWatch));
        }
    }

    MavenWatch(Path directoryToWatch) throws IOException {
        // TODO: which one to use? whats the difference
        //this.watcher = FileSystems.getDefault().newWatchService();
        this.watcher = directoryToWatch.getFileSystem().newWatchService();
        this.watchedDirectories = new HashMap<>();
        LOGGER.log(Level.FINE, "SCANNING {0}", directoryToWatch);
        if (isWindows()) {
            registerDirectoryToBeWatched(directoryToWatch);
        } else {
            registerDirectoryAndSubDirectoriesToBeWatched(directoryToWatch);
        }
        LOGGER.log(Level.FINE, "SCANNING END", directoryToWatch);
    }

    /**
     * Process all events for watchedDirectories queued to the watcher
     */
    void processEvents() throws IOException {
        for (; ; ) {

            // wait for watchKey to be signalled
            WatchKey watchKey;
            try {
                watchKey = watcher.take();
            } catch (InterruptedException x) {
                LOGGER.log(Level.INFO, "Error {0}", x.getMessage());
                System.err.println(x);
                return;
            }

            Path watchedDirectory = watchedDirectories.get(watchKey);
            if (watchedDirectory == null) {
                LOGGER.log(Level.INFO, "WatchKey not recognized: {0}", watchKey);
                continue;
            }

            for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
                WatchEvent.Kind eventKind = watchEvent.kind();
                if (eventKind == OVERFLOW) {
                    continue;
                }
                WatchEvent<Path> fileSystemEvent = castEvent(watchEvent);
                Path changedFileName = fileSystemEvent.context();
                Path changedFilePath = watchedDirectory.resolve(changedFileName);
                //LOGGER.log(Level.FINE, "{0}", Arrays.asList(watchEvent.kind().name(), changedFilePath));

                // This check doesn't work, because the file is already deleted
                /*
                if (changedFilePath.toFile().isDirectory()) {
                    if (eventKind == ENTRY_CREATE) {
                        if (!isNotWindows()) {
                            // TODO: add to watched keys and watch
                        }
                    } else if (eventKind == ENTRY_DELETE) {
                        deleteTargetDirectories(changedFilePath);
                        if (!isNotWindows()) {
                            // TODO: remove from watched keys and unwatch
                        }
                    } else if (eventKind == ENTRY_MODIFY) {
                        deleteTargetDirectories(changedFilePath);
                        if (!isNotWindows()) {
                            // TODO: re-add to watched keys and re-watch
                        }
                    }
                } else {
                    if (changedFileName.toFile().getName().endsWith(SUFFIX_JAVA)) {
                        if (eventKind == ENTRY_DELETE || eventKind == ENTRY_MODIFY) {
                            findAndDeleteAllFiles(changedFilePath);
                        }
                    }
                }
                */
            }
        }
    }

    public boolean resetKeyAndRemoveFromSetIfDirectoryNoLongerAccessible(WatchKey key) {
        // reset key and remove from set if directory no longer accessible
        boolean valid = key.reset();
        if (!valid) {
            watchedDirectories.remove(key);
            // all directories are inaccessible
            if (watchedDirectories.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void deletedClasses(Path file) throws IOException {
        String srcPath = file.toFile().toURI().getPath();
        String clazz = srcPath.replaceFirst(MAVEN_SRC_JAVA, MAVEN_TARGET_CLASSES).replaceFirst(SUFFIX_JAVA, SUFFIX_CLASS);
        //String webInfClazz = srcPath.replaceFirst(MAVEN_SRC_JAVA, MAVEN_TARGET_WEB_APP_CLASSES).replaceFirst(SUFFIX_JAVA, SUFFIX_CLASS);

        File clazzFile = new File(clazz);
        deleteFile(clazzFile);
    }

    public void deleteIfTargetDirectoryIsEmpty(final WatchEvent watchEvent, final Path directory) throws IOException {
        String srcPath = directory.toFile().toURI().getPath();
        String targetDirectory = srcPath.replaceFirst(MAVEN_SRC_JAVA, MAVEN_TARGET_CLASSES);
        File targetDirectoryFile = new File(targetDirectory);
        if(targetDirectoryFile.isDirectory() && isDirectoryEmpty(targetDirectoryFile)) {
            LOGGER.log(Level.FINE, "{0}", Arrays.asList(watchEvent.kind().name(), targetDirectoryFile.toPath()));
            deleteFile(targetDirectoryFile);
        }
    }

    public void handleNewDirectoryCreation(WatchEvent.Kind kind, Path child) {
        if (kind == ENTRY_CREATE) {
            try {
                if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                    registerDirectoryAndSubDirectoriesToBeWatched(child);
                }
            } catch (IOException x) {
                LOGGER.log(Level.INFO, "Error {0}", x.getMessage());
            }
        }
    }

    public static void deleteDirectory(File file) throws IOException {
        if (file.isDirectory()) {
            if (isDirectoryEmpty(file)) {
                deleteEmptyDirectory(file);
            } else {
                for (String fileInDirectory : file.list()) {
                    deleteDirectory(new File(file, fileInDirectory));
                }
                if (isDirectoryEmpty(file)) {
                    deleteEmptyDirectory(file);
                }
            }
        } else {
            deleteFile(file);
        }
    }

    public static void deleteFile(File file) {
        boolean delete = file.delete();
        LOGGER.log(Level.FINE, "DELETED FILE {0}", Arrays.asList(file.getAbsolutePath(), delete));
    }

    public static void deleteEmptyDirectory(File file) {
        //boolean delete = file.delete();
        //LOGGER.log(Level.FINE, "DELETED DIR {0}", Arrays.asList(file.getAbsolutePath(), delete));
    }

    public static boolean isDirectoryEmpty(File file) {
        return file.list().length == 0;
    }

    public static boolean isWindows() {
        return (OS.indexOf("win") >= 0);
    }

    private static void configureLogger(Level level) {
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(level);
        LOGGER.addHandler(ch);
        LOGGER.setLevel(level);
    }

    private static void usage() {
        LOGGER.info("usage: java MavenWatchWindows dir");
        System.exit(-1);
    }
}
