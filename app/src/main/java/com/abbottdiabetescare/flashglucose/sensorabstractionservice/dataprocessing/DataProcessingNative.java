package com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing;

// GERADO automaticamente a partir do dexdump do app oficial FreeStyle Libre 2 BR.
// A libDataProcessing.so registra estes 47 metodos via RegisterNatives no JNI_OnLoad;
// as assinaturas precisam bater EXATAMENTE ou o registro falha. Ver as notas internas do projeto.
@SuppressWarnings("all")
public final class DataProcessingNative {
    static { System.loadLibrary("DataProcessing"); }

    public static native java.lang.Object[] $sas$B5bdb4b27(int p0);
    public static native void $sas$COI();
    public static native void $sas$E5bdb4b27(java.lang.Object p0, java.lang.Object p1);
    public static native int P1(int p0, int p1, byte[] p2, byte[] p3, byte[] p4);
    public static native byte[] P2(int p0, int p1, byte[] p2, byte[] p3, byte[] p4);
    public static native byte[] activationComplete(byte[] p0, int p1, int p2, byte[] p3, byte[] p4);
    public static native void dpEnableDebugLogging(byte[] p0);
    public static native void dpSetMaxAlgorithmVersion(int p0, byte[] p1);
    public static native void dpSetReaderCodeSimMode(int p0, byte[] p1);
    public static native byte getActivationCommand(int p0, byte[] p1, byte[] p2);
    public static native byte[] getActivationPayload(int p0, byte[] p1, byte[] p2, byte p3, byte[] p4);
    public static native byte[] getDecryptedNorthStarPayload(byte[] p0, byte[] p1, byte[] p2);
    public static native byte[] getEnableStreamingPayload(int p0, byte[] p1, byte[] p2, byte p3, int p4, byte[] p5);
    public static native byte[] getEncryptedNorthStarPayload(byte[] p0, byte[] p1, byte[] p2);
    public static native boolean getNeedsReaderInfoForActivation(int p0, byte[] p1);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.MemoryRegion getNextRegionToRead(int p0, byte[] p1, byte[] p2, byte[] p3, int p4, byte[] p5);
    public static native boolean getPatchTimeValues(int p0, byte[] p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p2, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p3, byte[] p4);
    public static native int getProductFamily(int p0, byte[] p1, byte[] p2);
    public static native long getStatusCodeNative(java.lang.String p0, int p1, int p2, int p3, boolean p4, byte[] p5);
    public static native byte[] getStreamingUnlockPayload(int p0, byte[] p1, byte[] p2, byte p3, int p4, int p5, byte[] p6);
    public static native int getTotalMemorySize(int p0, byte[] p1);
    public static native void initializeNative(java.lang.Object p0, byte[] p1);
    public static native boolean isPatchSupported(int p0, byte[] p1, int p2, int p3, int p4, com.abbottdiabetescare.flashglucose.sensorabstractionservice.ApplicationRegion p5, byte[] p6);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingResult processScan(int p0, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AlarmConfiguration p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.NonActionableConfiguration p2, com.abbottdiabetescare.flashglucose.sensorabstractionservice.GlucoseValueRange p3, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AttenuationConfiguration p4, boolean p5, byte[] p6, byte[] p7, byte[] p8, int p9, int p10, int p11, int p12, boolean p13, int p14, byte[] p15, byte[] p16, byte[] p17, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p18, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p19, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p20, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p21, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p22, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p23, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p24, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p25, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p26, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p27, byte[] p28);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingResult processStream(int p0, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AlarmConfiguration p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.NonActionableConfiguration p2, com.abbottdiabetescare.flashglucose.sensorabstractionservice.GlucoseValueRange p3, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AttenuationConfiguration p4, byte[] p5, byte[] p6, byte p7, byte[] p8, int p9, int p10, int p11, int p12, boolean p13, byte[] p14, byte[] p15, byte[] p16, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p17, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p18, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p19, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p20, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p21, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p22, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p23, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.Out p24, byte[] p25);
    public static native byte[] activationComplete(int p0, byte[] p1, byte[] p2);
    public static native void enableDebugLogging();
    public static native int gen2Process1(int p0, int p1, byte[] p2, byte[] p3);
    public static native byte[] gen2Process2(int p0, int p1, byte[] p2, byte[] p3);
    public static native byte getActivationCommand(byte[] p0);
    public static native byte[] getActivationPayload(byte[] p0, byte[] p1, byte p2);
    public static native byte[] getDecryptedNorthStarPayload(byte[] p0, byte[] p1);
    public static native byte[] getEnableStreamingPayload(byte[] p0, byte[] p1, byte p2, int p3);
    public static native byte[] getEncryptedNorthStarPayload(byte[] p0, byte[] p1);
    public static native boolean getNeedsReaderInfoForActivation();
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.MemoryRegion getNextRegionToRead(byte[] p0, byte[] p1, byte[] p2, int p3);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.PatchTimeValues getPatchTimeValues(byte[] p0);
    public static native int getProductFamily(byte[] p0);
    public static native long getStatusCode(java.lang.String p0, int p1, int p2, int p3, boolean p4);
    public static native byte[] getStreamingUnlockPayload(byte[] p0, byte[] p1, byte p2, int p3, int p4);
    public static native int getTotalMemorySize();
    public static native void initialize(java.lang.Object p0);
    public static native boolean isPatchSupported(byte[] p0, com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingSupportedSensorConfig p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.ApplicationRegion p2);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingScanOutputs processScan(com.abbottdiabetescare.flashglucose.sensorabstractionservice.AlarmConfiguration p0, com.abbottdiabetescare.flashglucose.sensorabstractionservice.NonActionableConfiguration p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.GlucoseValueRange p2, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AttenuationConfiguration p3, boolean p4, byte[] p5, byte[] p6, byte[] p7, int p8, int p9, int p10, int p11, boolean p12, int p13, byte[] p14, byte[] p15, byte[] p16);
    public static native com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingOutputs processStream(com.abbottdiabetescare.flashglucose.sensorabstractionservice.AlarmConfiguration p0, com.abbottdiabetescare.flashglucose.sensorabstractionservice.NonActionableConfiguration p1, com.abbottdiabetescare.flashglucose.sensorabstractionservice.GlucoseValueRange p2, com.abbottdiabetescare.flashglucose.sensorabstractionservice.AttenuationConfiguration p3, byte[] p4, byte[] p5, byte p6, byte[] p7, int p8, int p9, int p10, int p11, boolean p12, byte[] p13, byte[] p14, byte[] p15);
    public static native void setMaxAlgorithmVersion(int p0);
    public static native void setReaderCodeSimMode(int p0);
}
