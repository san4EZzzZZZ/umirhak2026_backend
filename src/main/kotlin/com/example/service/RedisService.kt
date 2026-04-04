package com.example.service

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI

class RedisService(redisUrl: String) {
    private val pool: JedisPool

    init {
        val config = JedisPoolConfig().apply {
            maxTotal = 32
            maxIdle = 8
            minIdle = 1
            testOnBorrow = true
        }
        pool = JedisPool(config, URI(redisUrl))
    }

    fun withJedis(block: (redis.clients.jedis.Jedis) -> Unit) {
        pool.resource.use(block)
    }

    fun <T> read(block: (redis.clients.jedis.Jedis) -> T): T {
        pool.resource.use { return block(it) }
    }

    fun rateLimit(key: String, limit: Int, windowSeconds: Long): Boolean {
        return read { jedis ->
            val current = jedis.incr(key)
            if (current == 1L) {
                jedis.expire(key, windowSeconds)
            }
            current <= limit
        }
    }

    fun setJson(key: String, value: String, ttlSeconds: Long) {
        withJedis { jedis -> jedis.setex(key, ttlSeconds, value) }
    }

    fun getJson(key: String): String? = read { jedis -> jedis.get(key) }

    fun delete(vararg keys: String) {
        if (keys.isEmpty()) return
        withJedis { jedis -> jedis.del(*keys) }
    }

    fun close() {
        pool.close()
    }
}

