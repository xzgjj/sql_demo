package com.minidb.mvcc;

import java.util.Set;

public class ReadView {
    private final long creatorTxnId;
    private final long lowWatermark;
    private final long highWatermark;
    private final Set<Long> activeTxnIds;

    public ReadView(long creatorTxnId, long lowWatermark, long highWatermark,
                    Set<Long> activeTxnIds) {
        this.creatorTxnId = creatorTxnId;
        this.lowWatermark = lowWatermark;
        this.highWatermark = highWatermark;
        this.activeTxnIds = Set.copyOf(activeTxnIds);
    }

    public long creatorTxnId() {
        return creatorTxnId;
    }

    public long lowWatermark() {
        return lowWatermark;
    }

    public long highWatermark() {
        return highWatermark;
    }

    public Set<Long> activeTxnIds() {
        return activeTxnIds;
    }

    public boolean isVisible(long createdBy, long deletedBy) {
        return createdVisible(createdBy) && !deletedVisible(deletedBy);
    }

    private boolean createdVisible(long createdBy) {
        if (createdBy == creatorTxnId) {
            return true;
        }
        if (createdBy < lowWatermark) {
            return true;
        }
        if (createdBy >= highWatermark) {
            return false;
        }
        return !activeTxnIds.contains(createdBy);
    }

    private boolean deletedVisible(long deletedBy) {
        if (deletedBy == 0) {
            return false;
        }
        if (deletedBy == creatorTxnId) {
            return true;
        }
        if (deletedBy < lowWatermark) {
            return true;
        }
        if (deletedBy >= highWatermark) {
            return false;
        }
        return !activeTxnIds.contains(deletedBy);
    }
}
