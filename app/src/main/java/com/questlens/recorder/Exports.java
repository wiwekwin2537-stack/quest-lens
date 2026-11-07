package com.questlens.recorder;

import android.content.Context;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Exports {
    static File directory(Context context) {
        return new File(context.getFilesDir(), "demonstrations");
    }

    static File latest(Context context, String extension) {
        File[] files = directory(context).listFiles(
                (dir, name) -> name.startsWith("quest-demo-") && name.endsWith(".zip"));
        if (files == null || files.length == 0) return null;
        File zip = Arrays.stream(files).max(Comparator.comparingLong(File::lastModified)).orElse(null);
        if ("zip".equals(extension)) return zip;
        File video = new File(zip.getParentFile(), zip.getName().replaceFirst("\\.zip$", ".mp4"));
        return video.isFile() ? video : null;
    }

    static void deleteSaved(Context context) {
        File[] files = directory(context).listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    static void cleanStaging(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".partial"));
        if (files != null) for (File file : files) file.delete();
    }

    static void publish(File source, String stem, String manifest) throws IOException {
        File directory = source.getParentFile();
        File archiveTemp = new File(directory, stem + ".zip.partial");
        File archive = new File(directory, stem + ".zip");
        File video = new File(directory, stem + ".mp4");
        boolean published = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archiveTemp)))) {
                zip.setLevel(0);
                zip.putNextEntry(new ZipEntry("recording.mp4"));
                Files.copy(source.toPath(), zip);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("session.json"));
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(source.toPath(), video.toPath(), StandardCopyOption.ATOMIC_MOVE);
            Files.move(archiveTemp.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE);
            published = true;
            File[] old = directory.listFiles((dir, name) -> name.startsWith("quest-demo-")
                    && !name.equals(video.getName()) && !name.equals(archive.getName()));
            if (old != null) for (File file : old) file.delete();
        } finally {
            if (!published) {
                source.delete();
                video.delete();
                archiveTemp.delete();
            }
        }
    }
}
