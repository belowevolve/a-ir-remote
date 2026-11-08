package mov.film;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Optional cache-only route added to the original TvArt provider. */
public final class CleanArtwork {
  private CleanArtwork() {}

  public static ParcelFileDescriptor openIfClean(Context context, Uri uri, String mode)
      throws FileNotFoundException {
    List<String> path = uri.getPathSegments();
    if (path.isEmpty() || !"clean".equals(path.get(0))) return null;
    if (!"content".equals(uri.getScheme())
        || !"mov.film.tvart".equals(uri.getAuthority())
        || !"/clean".equals(uri.getEncodedPath())
        || uri.getFragment() != null
        || !"r".equals(mode)
        || uri.getQueryParameterNames().size() != 1
        || uri.getQueryParameters("poster").size() != 1) {
      throw new FileNotFoundException("Unsupported clean artwork request");
    }
    String original = uri.getQueryParameter("poster");
    if (!CleanPosterCache.validUrl(original)) {
      throw new FileNotFoundException("Unsupported poster URL");
    }
    // Use the app's own cache key normalization, matching ImageLoader.now(..., 300).
    String effective = GeMovieSource.lighter(Tmdb.lighter(original, 300), 300);
    File directory = new File(context.getCacheDir(), "posters");
    for (String candidate : new String[] {effective, original}) {
      if (!CleanPosterCache.validUrl(candidate)) continue;
      try {
        File file = CleanPosterCache.existing(directory, candidate);
        if (file == null) continue;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) continue;
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
      } catch (IOException unavailable) {
        // The normal image-cache eviction may race this read. Try the other key.
      }
    }
    throw new FileNotFoundException("Clean artwork is not cached");
  }
}
