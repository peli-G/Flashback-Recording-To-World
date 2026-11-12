package com.moulberry.fbrtoworld;

final class VersionSupport {

    private VersionSupport() {}

    static boolean accepts(int recordingDataVersion, String recordingVersionName) {
        if (recordingDataVersion == 0) return true;
        return recordingDataVersion == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }
}
