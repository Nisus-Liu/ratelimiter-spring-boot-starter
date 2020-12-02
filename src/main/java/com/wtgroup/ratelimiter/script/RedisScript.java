package com.wtgroup.ratelimiter.script;

/**
 * @author dafei
 * @version 0.1
 * @date 2020/12/2 15:58
 */
public interface RedisScript {

    // /**lua 脚本 sha1 值
    //  * @return
    //  */
    // String getSha1();

    /**lua 脚本
     * @return
     */
    String getScriptAsString();

}
