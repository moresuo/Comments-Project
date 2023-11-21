package com.hmdp.utils;

/**
 * @version 1.0
 * @Author moresuo
 * @Date 2023/10/7 16:59
 * @注释
 */
public interface Lock {
    boolean tryLock(long timeOut);
    void unLock();
}
