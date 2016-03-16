package com.hanhuy.android.irc;

/**
 * @author pfnguyen
 */
public class ScalaWorkaround {
    public static <T> T sync(final Object lock, final scala.Function0<T> f) {
        synchronized(lock) {
            return f.apply();
        }
    }
}
