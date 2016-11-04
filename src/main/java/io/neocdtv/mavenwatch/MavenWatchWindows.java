package io.neocdtv.mavenwatch;

import com.sun.nio.file.ExtendedWatchEventModifier;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardWatchEventKinds.*;

public class MavenWatchWindows {

    private static Logger LOGGER = Logger.getLogger(MavenWatchWindows.class.getClass().getName());
    private static String SUFFIX_JAVA = ".java";
    private static String MAVEN_SRC_MAIN_JAVA = "/src/main/java/";
    private static String MAVEN_SRC_TEST_JAVA = "/src/test/java/";
    private static String MAVEN_TARGET_CLASSES = "/target/classes/";
    private static String MAVEN_TARGET_GENERATED_SOURCES = "/target/generated-sources/annotations/";
    private static String MAVEN_TARGET_TEST_CLASSES = "/target/test-classes/";
    private static String MAVEN_TARGET_GENERATED_TEST_SOURCES = "/target/generated-test-sources/test-annotations/";
    private static String OS = System.getProperty("os.name").toLowerCase();

    private WatchService watcher;
    private Map<WatchKey, Path> watchedDirectories;

    public static void main(String[] args) throws IOException {
        if (isNotWindows()) {
            wrongOS();
        }
        if (args.length == 0) {
            usage();
        }

        configureLogger(Level.INFO);
        LOGGER.log(Level.FINE, "args {0}", args);
        registerDirectoryAndProcessItsEvents(args[0]);
    }

    public static void registerDirectoryAndProcessItsEvents(String dirToWatch) throws IOException {
        Path dir = Paths.get(dirToWatch);
        new MavenWatchWindows(dir).processEvents();
    }

    static <T> WatchEvent<T> castEvent(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    private void registerDirectoryToBeWatched(Path directoryToWatch) throws IOException {
        LOGGER.log(Level.FINE, "REGISTER: {0}", directoryToWatch);
        final WatchEvent.Modifier[] modifiers = new WatchEvent.Modifier[1];
        modifiers[0] = ExtendedWatchEventModifier.FILE_TREE;
        final WatchKey watchedDirectory = directoryToWatch.register(watcher, new WatchEvent.Kind<?>[]{ENTRY_DELETE, ENTRY_MODIFY}, modifiers);
        watchedDirectories.put(watchedDirectory, directoryToWatch);
    }

    MavenWatchWindows(Path directoryToWatch) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.watchedDirectories = new HashMap<>();
        registerDirectoryToBeWatched(directoryToWatch);
    }

    void processEvents() throws IOException {
        for (; ; ) {
            WatchKey watchKey;
            try {
                watchKey = watcher.take();
            } catch (InterruptedException x) {
                LOGGER.log(Level.INFO, "ERROR {0}", x.getMessage());
                return;
            }
            Path watchedDirectory = watchedDirectories.get(watchKey);
            if (watchedDirectory == null) {
                LOGGER.log(Level.INFO, "WatchKey not recognized: {0}", watchKey);
                continue;
            } else {
                for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
                    WatchEvent.Kind eventKind = watchEvent.kind();
                    if (eventKind == OVERFLOW) {
                        continue;
                    }
                    WatchEvent<Path> fileSystemEvent = castEvent(watchEvent);
                    Path changedFileName = fileSystemEvent.context();
                    Path changedFilePath = watchedDirectory.resolve(changedFileName);
                    if (changedFileName.toFile().getName().endsWith(SUFFIX_JAVA)) {
                        if (eventKind == ENTRY_DELETE || eventKind == ENTRY_MODIFY) {
                            LOGGER.log(Level.FINE, "{0}", Arrays.asList(watchEvent.kind().name(), changedFilePath));
                            findAndDeleteAllFiles(changedFilePath, MAVEN_TARGET_CLASSES, MAVEN_TARGET_TEST_CLASSES);
                            findAndDeleteAllFiles(changedFilePath, MAVEN_TARGET_GENERATED_SOURCES, MAVEN_TARGET_GENERATED_TEST_SOURCES);
                        }
                    } else {
                        deleteEmptyDirectory(watchEvent, changedFilePath);
                    }
                }
            }

            // TODO: is this method needed?
            if (resetKeyAndRemoveFromSetIfDirectoryNoLongerAccessible(watchKey)) {
                break;
            }
        }
    }

    public boolean resetKeyAndRemoveFromSetIfDirectoryNoLongerAccessible(WatchKey key) {
        // reset key and remove from set if directory no longer accessible
        final boolean valid = key.reset();
        if (!valid) {
            watchedDirectories.remove(key);
            // all directories are inaccessible
            if (watchedDirectories.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void findAndDeleteAllFiles(final Path file, final String replaceSrcJavaWith, final String replaceSrcTestWith) throws IOException {
        final String srcPath = file.toFile().toURI().getPath();
        if (isSrcMainJava(srcPath)) {
            final String targetClazz = srcPath.replaceFirst(MAVEN_SRC_MAIN_JAVA, replaceSrcJavaWith).replaceFirst(SUFFIX_JAVA, "");
            final File targetClazzFile = new File(targetClazz);
            findAndDelete(targetClazzFile);
        } else if (isSrcTestJava(srcPath)) {
            final String targetClazz = srcPath.replaceFirst(MAVEN_SRC_TEST_JAVA, replaceSrcTestWith).replaceFirst(SUFFIX_JAVA, "");
            final File targetClazzFile = new File(targetClazz);
            findAndDelete(targetClazzFile);
        }
    }

    public void deleteEmptyDirectory(final WatchEvent watchEvent, final Path directory) throws IOException {
        String srcPath = directory.toFile().toURI().getPath();
        if (isSrcMainJava(srcPath)) {
            final String targetDirectory = srcPath.replaceFirst(MAVEN_SRC_MAIN_JAVA, MAVEN_TARGET_CLASSES);
            final File targetDirectoryFile = new File(targetDirectory);
            if (targetDirectoryFile.isDirectory() && isDirectoryEmpty(targetDirectoryFile)) {
                LOGGER.log(Level.FINE, "{0}", Arrays.asList(watchEvent.kind().name(), targetDirectoryFile.toPath()));
                deleteFile(targetDirectoryFile);
            }
        } else if (isSrcTestJava(srcPath)) {
            final String targetDirectory = srcPath.replaceFirst(MAVEN_SRC_TEST_JAVA, MAVEN_TARGET_TEST_CLASSES);
            final File targetDirectoryFile = new File(targetDirectory);
            if (targetDirectoryFile.isDirectory() && isDirectoryEmpty(targetDirectoryFile)) {
                LOGGER.log(Level.FINE, "{0}", Arrays.asList(watchEvent.kind().name(), targetDirectoryFile.toPath()));
                deleteFile(targetDirectoryFile);
            }
        }
    }

    boolean isSrcMainJava(final String srcPath) {
        return srcPath.contains(MAVEN_SRC_MAIN_JAVA);
    }

    public boolean isSrcTestJava(final String srcPath) {
        return srcPath.contains(MAVEN_SRC_TEST_JAVA);
    }

    public static void findAndDelete(final File fileWitoutSuffixInTargetDirectory) {
        final String classNameWithOutSuffix = fileWitoutSuffixInTargetDirectory.getName();
        final String targetDirectory = fileWitoutSuffixInTargetDirectory.getParentFile().toURI().getPath();
        final File targetDirectoryFile = new File(targetDirectory);
        File[] classFilesToDelete = targetDirectoryFile.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith(classNameWithOutSuffix);
            }
        });
        if (classFilesToDelete != null) {
            for (File toDelete : classFilesToDelete) {
                deleteFile(toDelete);
            }
        }
    }

    public static void deleteFile(final File file) {
        boolean delete = file.delete();
        LOGGER.log(Level.INFO, "DELETED FILE {0}", Arrays.asList(file.getAbsolutePath(), delete, file.isDirectory()));
    }

    public static boolean isDirectoryEmpty(final File file) {
        return file.list().length == 0;
    }

    public static boolean isNotWindows() {
        return !(OS.indexOf("win") >= 0);
    }

    private static void configureLogger(final Level level) {
        final ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(level);
        LOGGER.addHandler(ch);
        LOGGER.setLevel(level);
    }

    private static void usage() {
        LOGGER.info("usage: rootDirToWatch");
        System.exit(-1);
    }

    private static void wrongOS() {
        LOGGER.info("This program runs only on windows");
        System.exit(-1);
    }
}
