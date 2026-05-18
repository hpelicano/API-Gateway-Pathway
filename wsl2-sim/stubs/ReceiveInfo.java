package com.tandem.ext.guardian;

/** Stub de ReceiveInfo para compilar sin tdmext.jar en WSL2. */
public class ReceiveInfo {
    private int fileNumber;
    private int syncId;

    public int getFileNumber() { return fileNumber; }
    public int getSyncId()     { return syncId; }
    public void setFileNumber(int fn)  { this.fileNumber = fn; }
    public void setSyncId(int sid)     { this.syncId = sid; }
}
