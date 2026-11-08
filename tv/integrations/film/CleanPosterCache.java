package mov.film;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Resolves only existing image-cache entries; never downloads or creates a file. */
final class CleanPosterCache {
  static final long MAX_BYTES = 5L * 1024 * 1024;

  private CleanPosterCache() {}

  static boolean validUrl(String value) {
    if (value == null || value.length() > 8192) return false;
    try {
      URI uri = new URI(value);
      return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null;
    } catch (Exception invalid) {
      return false;
    }
  }

  static String key(String value) {
    if (!validUrl(value)) throw new IllegalArgumentException("Unsupported poster URL");
    try {
      byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder name = new StringBuilder(32);
      for (byte part : hash) {
        name.append(Character.forDigit((part >>> 4) & 15, 16));
        name.append(Character.forDigit(part & 15, 16));
      }
      return name.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static File existing(File directory, String url) throws IOException {
    File root = directory.getCanonicalFile();
    File candidate = new File(root, key(url));
    // A hash filename alone is not enough if a cache entry is a symbolic link.
    if (!root.equals(candidate.getCanonicalFile().getParentFile())) return null;
    if (!candidate.isFile() || candidate.length() <= 0 || candidate.length() > MAX_BYTES) return null;
    return candidate;
  }
}
