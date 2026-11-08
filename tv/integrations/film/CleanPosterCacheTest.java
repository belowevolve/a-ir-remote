package mov.film;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host regression checks; Android provider decoding is verified on the TV separately. */
public final class CleanPosterCacheTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    String poster = "https://image.tmdb.org/t/p/w342/example.jpg";
    check(CleanPosterCache.key(poster).matches("[0-9a-f]{32}"), "Native cache filename");
    check(!CleanPosterCache.validUrl("file:///etc/passwd"), "No local file URL");
    check(!CleanPosterCache.validUrl("content://other/private"), "No content proxy");
    check(!CleanPosterCache.validUrl("https://user:pass@example.com/a"), "No credentials");
    check(!CleanPosterCache.validUrl("https://example.com/a#fragment"), "No fragments");
    check(!CleanPosterCache.validUrl("https://"), "No missing host");
    Path temporary = Files.createTempDirectory("film-clean-cache-test-");
    try {
      File root = temporary.resolve("posters").toFile();
      check(CleanPosterCache.existing(root, poster) == null, "Missing cache is a miss");
      check(!root.exists(), "Lookup must not create the cache");
      Files.createDirectory(root.toPath());
      Path entry = root.toPath().resolve(CleanPosterCache.key(poster));
      Files.write(entry, new byte[] {1, 2, 3});
      check(CleanPosterCache.existing(root, poster).equals(entry.toFile().getCanonicalFile()), "Existing raw entry");
      Files.delete(entry);
      Path outside = Files.write(temporary.resolve("private"), new byte[] {4});
      Files.createSymbolicLink(entry, outside);
      check(CleanPosterCache.existing(root, poster) == null, "Reject symlink outside cache");
      Files.delete(entry);
      try (RandomAccessFile oversized = new RandomAccessFile(entry.toFile(), "rw")) {
        oversized.setLength(CleanPosterCache.MAX_BYTES + 1);
      }
      check(CleanPosterCache.existing(root, poster) == null, "Bound individual image size");
      Files.delete(entry);
      Files.write(entry, new byte[0]);
      check(CleanPosterCache.existing(root, poster) == null, "Reject empty image");
      Files.delete(entry);
      Files.delete(outside);
      Files.delete(root.toPath());
    } finally {
      Files.delete(temporary);
    }
    System.out.println("CleanPosterCache: host regression checks passed");
  }
}
