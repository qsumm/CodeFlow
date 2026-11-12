package com.ainative.notification;

public interface NotifyChannel {
    String name();
    boolean deliver(NotificationDO notification);
}
