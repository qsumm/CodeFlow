package com.ainative.im;

import com.ainative.common.error.AlreadyLoggedException;

final class ImLogSupport {
    private ImLogSupport() {
    }

    static AlreadyLoggedException safeThrowable(Throwable failure) {
        return AlreadyLoggedException.from(failure);
    }
}
