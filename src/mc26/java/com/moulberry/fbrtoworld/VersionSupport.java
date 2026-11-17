package com.moulberry.fbrtoworld;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionSupport {

    private static final Pattern RELEASE = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.\\d+)?$");

    private VersionSupport() {}

    static boolean accepts(int recordingDataVersion, String recordingVersionName) {
        if (recordingDataVersion == 0) return true;
        if (recordingDataVersion == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()) return true;
        return sameRelease(recordingVersionName, net.minecraft.SharedConstants.getCurrentVersion().name());
    }

    private static boolean sameRelease(String recorded, String current) {
        if (recorded == null || current == null) return false;
        Matcher a = RELEASE.matcher(recorded);
        Matcher b = RELEASE.matcher(current);
        return a.matches() && b.matches() && a.group(1).equals(b.group(1)) && a.group(2).equals(b.group(2));
    }
}
