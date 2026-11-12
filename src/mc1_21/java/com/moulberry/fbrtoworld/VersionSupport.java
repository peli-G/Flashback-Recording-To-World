package com.moulberry.fbrtoworld;

final class VersionSupport {

    private VersionSupport() {}

    static boolean accepts(int recordingDataVersion, String recordingVersionName) {
        if (recordingDataVersion == 0) return true;
        if (recordingDataVersion == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()) return true;
        return "1.21.10".equals(recordingVersionName) || recordingDataVersion == 4556;
    }
}
