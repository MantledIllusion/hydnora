package com.mantledillusion.cache.hydnora;

import org.junit.Assert;
import org.junit.Test;

import java.lang.ref.WeakReference;

public class GCReferenceTest {

    private static final class CollectableCache extends HydnoraCache<String, Object> {

        private CollectableCache() {
            super(ReferenceMode.WEAK);
        }

        @Override
        protected Object load(String id) throws Exception {
            return new Object();
        }
    }

    private static final String ID = "a";

    @Test
    public void testGarbageCollectEntry() throws InterruptedException {
        CollectableCache cache = new CollectableCache();
        cache.get(ID);

        WeakReference<Object> gcCompletionCheckReference = new WeakReference<>(new Object());
        System.gc();
        long ms = System.currentTimeMillis();
        do {
            if (System.currentTimeMillis()-ms > 10000) {
                throw new IllegalStateException("Garbage collection was expected to be completed in under 10 seconds!");
            }
            Thread.sleep(50);
        } while (gcCompletionCheckReference.get() != null);

        Assert.assertFalse(cache.contains(ID));
    }
}
