package yier.bubu.algorithm.cache;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LruCacheTest {

    @Test
    public void put_shouldEvictLeastRecentlyUsedEntryWhenCapacityIsExceeded() {
        LruCache<String, Integer> cache = new LruCache<String, Integer>(2);

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        Assert.assertFalse(cache.containsKey("A"));
        Assert.assertEquals(Arrays.asList("B", "C"), cache.keysFromLeastToMostRecentlyUsed());
        Assert.assertEquals(2, cache.size());
    }

    @Test
    public void get_shouldPromoteEntryToMostRecentlyUsed() {
        LruCache<String, Integer> cache = new LruCache<String, Integer>(2);
        cache.put("A", 1);
        cache.put("B", 2);

        Assert.assertEquals(Integer.valueOf(1), cache.get("A"));
        cache.put("C", 3);

        Assert.assertFalse(cache.containsKey("B"));
        Assert.assertEquals(Arrays.asList("A", "C"), cache.keysFromLeastToMostRecentlyUsed());
    }

    @Test
    public void put_shouldUpdateExistingEntryAndPromoteIt() {
        LruCache<String, Integer> cache = new LruCache<String, Integer>(2);
        cache.put("A", 1);
        cache.put("B", 2);

        Assert.assertEquals(Integer.valueOf(1), cache.put("A", 10));
        cache.put("C", 3);

        Assert.assertFalse(cache.containsKey("B"));
        Assert.assertEquals(Integer.valueOf(10), cache.get("A"));
        Assert.assertEquals(Arrays.asList("C", "A"), cache.keysFromLeastToMostRecentlyUsed());
    }

    @Test
    public void remove_shouldUnlinkEntryFromCacheOrder() {
        LruCache<String, Integer> cache = new LruCache<String, Integer>(3);
        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);

        Assert.assertEquals(Integer.valueOf(2), cache.remove("B"));

        Assert.assertFalse(cache.containsKey("B"));
        Assert.assertEquals(Arrays.asList("A", "C"), cache.keysFromLeastToMostRecentlyUsed());
        Assert.assertEquals(2, cache.size());
    }

    @Test
    public void clear_shouldRemoveAllEntries() {
        LruCache<String, Integer> cache = new LruCache<String, Integer>(2);
        cache.put("A", 1);
        cache.put("B", 2);

        cache.clear();

        Assert.assertTrue(cache.isEmpty());
        Assert.assertEquals(Collections.<String>emptyList(), cache.keysFromLeastToMostRecentlyUsed());
        Assert.assertNull(cache.get("A"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructor_shouldRejectNonPositiveCapacity() {
        new LruCache<String, Integer>(0);
    }
}
