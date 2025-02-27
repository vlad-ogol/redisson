/**
 * Copyright (c) 2013-2024 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.api.listener;

import org.redisson.api.ObjectListener;

/**
 * Redisson Object Event listener for local cache update event published by Redis.
 *
 * @author Nikita Koksharov
 *
 */
public interface LocalCacheUpdateListener<K, V> extends ObjectListener {

    /**
     * Invoked on event of map entry udpate
     *
     * @param key key to update
     * @param value new value
     */
    void onUpdate(K key, V value);

}
