package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.ZipUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipFile;

/**
 * Nakama Craft: the NakamaCraft modpack ships inside this APK (assets/nakamacraft.mrpack). On first launch, and
 * whenever the app brings a newer pack, it is unpacked into one fixed instance folder, so worlds and settings
 * survive updates, and the "Nakama Craft" profile is created and selected. Fabric and the game files download
 * in the background, the same way an imported modpack does. The player signs in and presses Play.
 */
public final class NakamaCraftSetup {
    private static final String TAG = "NakamaCraft";
    private static final String PACK = "nakamacraft.mrpack";
    private static final String PROFILE = "nakamacraft";
    private static final String DIR = "custom_instances/nakamacraft";
    private static final String PREF = "nakamacraft_pack_version";

    /** Main thread, before the menu reads the selected profile. Only the loader download runs in the background. */
    public static void run(Context ctx) {
        try {
            File pack = new File(Tools.DIR_CACHE, PACK);
            try (InputStream in = ctx.getAssets().open(PACK); OutputStream out = new FileOutputStream(pack)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            JsonObject index;
            try (ZipFile z = new ZipFile(pack)) {
                index = JsonParser.parseString(Tools.read(ZipUtils.getEntryStream(z, "modrinth.index.json"))).getAsJsonObject();
            }
            String version = index.get("versionId").getAsString();
            JsonObject deps = index.getAsJsonObject("dependencies");
            ModLoader loader = new ModLoader(ModLoader.MOD_LOADER_FABRIC, deps.get("fabric-loader").getAsString(), deps.get("minecraft").getAsString());

            SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
            if (LauncherProfiles.mainProfileJson == null) LauncherProfiles.load();
            boolean have = LauncherProfiles.mainProfileJson.profiles.containsKey(PROFILE);
            boolean fresh = !version.equals(prefs.getString(PREF, "")) || !have;
            if (fresh) {
                File dest = new File(Tools.DIR_GAME_HOME, DIR);
                File mods = new File(dest, "mods");
                File[] old = mods.listFiles();
                if (old != null) for (File f : old) {
                    String n = f.getName();
                    if (n.startsWith("nakamacraft-") || n.startsWith("fabric-api-")) f.delete();
                }
                try (ZipFile z = new ZipFile(pack)) {
                    ZipUtils.zipExtract(z, "overrides/", dest);
                }
                MinecraftProfile p = have ? LauncherProfiles.mainProfileJson.profiles.get(PROFILE) : MinecraftProfile.getDefaultProfile();
                p.name = "Nakama Craft";
                p.gameDir = "./" + DIR;
                p.lastVersionId = loader.getVersionId();
                LauncherProfiles.mainProfileJson.profiles.put(PROFILE, p);
                LauncherProfiles.write();
                prefs.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, PROFILE).putString(PREF, version).apply();
                Log.i(TAG, "Installed NakamaCraft pack " + version);
            }
            // Fabric is checked on its own, every launch: an install cut short (app closed, no network) gets
            // finished next time instead of leaving a profile that can't start.
            String id = loader.getVersionId();
            if (!new File(Tools.DIR_HOME_VERSION + "/" + id + "/" + id + ".json").exists()) {
                PojavApplication.sExecutorService.execute(() -> {
                    try {
                        loader.getDownloadTask(new NotificationDownloadListener(ctx, loader)).run();
                    } catch (Throwable t) {
                        Log.e(TAG, "Fabric install failed", t);
                    }
                });
            }
            pack.delete();
        } catch (Throwable t) {
            Log.e(TAG, "NakamaCraft setup failed", t);
        }
        installBundledJava(ctx);
    }

    /**
     * Minecraft 26.1 needs Java 25. On arm64 phones the runtime ships inside the APK (assets/components/nakamacraft),
     * so nothing has to come from GitHub, which is slow or blocked in some places. It installs under the same name
     * Amethyst's own download uses, so the launcher finds it. Other architectures still download it on first Play.
     */
    private static void installBundledJava(Context ctx) {
        final String asset = "components/nakamacraft/jre25-arm64.tar.xz";
        final String name = "External-25";
        if (Architecture.getDeviceArchitecture() != Architecture.ARCH_ARM64) return;
        if (MultiRTUtils.getExactJreName(25) != null) return;
        try {
            ctx.getAssets().open(asset).close();
        } catch (Exception e) {
            return; // a build without the bundled runtime
        }
        ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 0, R.string.global_unpacking, "Java 25");
        PojavApplication.sExecutorService.execute(() -> {
            try (InputStream in = ctx.getAssets().open(asset)) {
                MultiRTUtils.installRuntimeNamed(Tools.NATIVE_LIB_DIR, in, name);
                MultiRTUtils.postPrepare(name);
                Log.i(TAG, "Installed bundled Java 25");
            } catch (Throwable t) {
                Log.e(TAG, "Bundled Java 25 failed; it will download on Play instead", t);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
            }
        });
    }

    private NakamaCraftSetup() {}
}
