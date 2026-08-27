package com.vcam.live;

import java.io.ByteArrayOutputStream;

// 传输流视频解复用器
public final class MpegTsDemuxer {

    private static final int TS_PACKET_SIZE = 188;
    private static final byte SYNC_BYTE = 0x47;

    public interface VideoCallback {
        void onVideoPayload(byte[] naluData, int offset, int length, long ptsUs);
    }

    private final VideoCallback videoCallback;

    private int videoPid = 0x100;
    private int pmtPid = -1;

    private final ByteArrayOutputStream videoPesBuffer = new ByteArrayOutputStream(64 * 1024);
    private long currentVideoPtsUs = -1;

    public MpegTsDemuxer(VideoCallback videoCallback) {
        this.videoCallback = videoCallback;
    }

    // 喂入传输流数据
    public void feed(byte[] data, int offset, int length) {
        int index = offset;
        int end = offset + length;

        while (index + TS_PACKET_SIZE <= end) {
            if (data[index] != SYNC_BYTE) {
                index++;
                continue;
            }

            processPacket(data, index);
            index += TS_PACKET_SIZE;
        }
    }

    // 处理单个数据包
    private void processPacket(byte[] pkt, int offset) {
        int b1 = pkt[offset + 1] & 0xFF;
        int b2 = pkt[offset + 2] & 0xFF;
        int b3 = pkt[offset + 3] & 0xFF;

        boolean payloadStart = (b1 & 0x40) != 0;
        int pid = ((b1 & 0x1F) << 8) | b2;
        int adaptControl = (b3 >> 4) & 0x03;

        int payloadOffset = offset + 4;
        if (adaptControl == 2) {
            return;
        } else if (adaptControl == 3) {
            int adaptLen = pkt[payloadOffset] & 0xFF;
            payloadOffset += 1 + adaptLen;
        }

        int payloadLen = offset + TS_PACKET_SIZE - payloadOffset;
        if (payloadLen <= 0) {
            return;
        }

        if (pid == 0) {
            parsePat(pkt, payloadOffset, payloadLen, payloadStart);
            return;
        }

        if (pid == pmtPid) {
            parsePmt(pkt, payloadOffset, payloadLen, payloadStart);
            return;
        }

        if (pid == videoPid) {
            if (payloadStart) {
                flushVideoPes();
                parseVideoPesHeader(pkt, payloadOffset, payloadLen);
            } else {
                if (videoPesBuffer.size() > 0) {
                    videoPesBuffer.write(pkt, payloadOffset, payloadLen);
                }
            }
        }
    }

    // 解析节目关联表
    private void parsePat(byte[] pkt, int offset, int len, boolean isStart) {
        if (isStart) {
            int pointer = pkt[offset] & 0xFF;
            offset += 1 + pointer;
            len -= 1 + pointer;
        }
        if (len < 8) return;
        int sectionLen = ((pkt[offset + 1] & 0x0F) << 8) | (pkt[offset + 2] & 0xFF);
        int pos = offset + 8;
        int end = offset + 3 + sectionLen - 4;

        while (pos + 4 <= end) {
            int programNum = ((pkt[pos] & 0xFF) << 8) | (pkt[pos + 1] & 0xFF);
            int pmt = ((pkt[pos + 2] & 0x1F) << 8) | (pkt[pos + 3] & 0xFF);
            if (programNum != 0) {
                pmtPid = pmt;
                break;
            }
            pos += 4;
        }
    }

    // 解析节目映射表
    private void parsePmt(byte[] pkt, int offset, int len, boolean isStart) {
        if (isStart) {
            int pointer = pkt[offset] & 0xFF;
            offset += 1 + pointer;
            len -= 1 + pointer;
        }
        if (len < 12) return;
        int sectionLen = ((pkt[offset + 1] & 0x0F) << 8) | (pkt[offset + 2] & 0xFF);
        int programInfoLen = ((pkt[offset + 10] & 0x0F) << 8) | (pkt[offset + 11] & 0xFF);

        int pos = offset + 12 + programInfoLen;
        int end = offset + 3 + sectionLen - 4;

        while (pos + 5 <= end) {
            int streamType = pkt[pos] & 0xFF;
            int elementaryPid = ((pkt[pos + 1] & 0x1F) << 8) | (pkt[pos + 2] & 0xFF);
            int esInfoLen = ((pkt[pos + 3] & 0x0F) << 8) | (pkt[pos + 4] & 0xFF);

            if (streamType == 0x1B) {
                videoPid = elementaryPid;
            }
            pos += 5 + esInfoLen;
        }
    }

    // 解析视频基本流头部
    private void parseVideoPesHeader(byte[] pkt, int offset, int len) {
        if (len < 9) return;
        if (pkt[offset] != 0 || pkt[offset + 1] != 0 || pkt[offset + 2] != 1) {
            return;
        }
        int streamId = pkt[offset + 3] & 0xFF;
        if ((streamId & 0xF0) != 0xE0) {
            return;
        }

        int flags2 = pkt[offset + 7] & 0xFF;
        int pesHeaderDataLen = pkt[offset + 8] & 0xFF;
        int ptsDtsFlags = (flags2 >> 6) & 0x03;

        currentVideoPtsUs = -1;
        int dataOffset = offset + 9 + pesHeaderDataLen;
        int dataLen = len - 9 - pesHeaderDataLen;

        if (ptsDtsFlags >= 2 && pesHeaderDataLen >= 5) {
            long p1 = (pkt[offset + 9] >> 1) & 0x07;
            long p2 = ((pkt[offset + 10] & 0xFF) << 7) | ((pkt[offset + 11] & 0xFF) >> 1);
            long p3 = ((pkt[offset + 12] & 0xFF) << 7) | ((pkt[offset + 13] & 0xFF) >> 1);
            long pts = (p1 << 30) | (p2 << 15) | p3;
            currentVideoPtsUs = pts * 1000000L / 90000L;
        }

        if (dataLen > 0) {
            videoPesBuffer.reset();
            videoPesBuffer.write(pkt, dataOffset, dataLen);
        }
    }

    // 输出视频有效负载
    private void flushVideoPes() {
        if (videoPesBuffer.size() > 0) {
            byte[] bytes = videoPesBuffer.toByteArray();
            if (videoCallback != null) {
                videoCallback.onVideoPayload(bytes, 0, bytes.length, currentVideoPtsUs);
            }
            videoPesBuffer.reset();
        }
    }
}
