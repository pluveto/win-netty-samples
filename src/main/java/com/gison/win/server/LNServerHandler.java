package com.gison.win.server;

import com.alibaba.fastjson.JSON;
import com.gison.win.util.NettyUtils;
import com.gison.win.util.RedisPool;
import com.gison.win.constant.TBOMProtocol;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


/**
 * @author <a href="mailto:gisonwin@qq.com">GisonWin</a>
 * @date 2019/8/29 11:09
 */
@Slf4j
@ChannelHandler.Sharable
@SuppressWarnings("unchecked")
public class LNServerHandler extends SimpleChannelInboundHandler<TBOMProtocol> {
    private final String sendContent = "HEARTBEAT";
    private Map<String, Channel> activeChannelMap;
    private Map<String, String> progessMap = new ConcurrentHashMap<String, String>();

    public LNServerHandler(Map<String, Channel> activeChannelMap) {
        this.activeChannelMap = activeChannelMap;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            switch (event.state()) {
                case READER_IDLE:
//                    handleReaderIdle(ctx);
                    log.warn("read idle invoked");
                    break;
                case WRITER_IDLE:
//                    handleWriterIdle(ctx);
                    log.warn("write idle invoked");
                    break;
                case ALL_IDLE:
                    handleAllIdle(ctx);
                    break;
                default:
                    log.info("unsupported event " + event);
                    break;
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void handleAllIdle(ChannelHandlerContext ctx) {
        TBOMProtocol protocol = NettyUtils.createHeartBeat(sendContent.getBytes());
        log.info("handle all idle = " + protocol.toString());
        ctx.writeAndFlush(protocol);
//        ctx.close();//test heartbeat
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TBOMProtocol msg) throws Exception {
        //???????????????????????????
        //??????????????????????????????????????????????????????.
        short businessCode = msg.getBusinessCode();
        String text = new String(msg.getContent(), CharsetUtil.UTF_8);
        switch (businessCode) {
            case 6: {
                // ?????????????????????,??????????????????.
                log.info("recv heartbeat from client {} ", ctx.channel().remoteAddress());
//                ctx.writeAndFlush(NettyUtils.createHeartBeat(sendContent.getBytes()));
                break;
            }
            case 1: {//??????TBOM
                String poll = null;
                do {
                    poll = RedisPool.getJedis().get(NettyUtils.TBOMLIST);
                    log.info("poll ==> {}", poll);
                    TimeUnit.SECONDS.sleep(1);
                    RedisPool.getJedis().close();
                } while (poll == null);

                log.debug(" tbom list =========> {}", poll);
                ctx.writeAndFlush(NettyUtils.createLoadTBOM(poll.getBytes()));
                log.info("client {}  load tbom success ,@  {}", ctx.channel().remoteAddress(), LocalDateTime.now());
                break;
            }
            case 2: {//??????TBOM??????
                String pid = "";
                Object id = JSON.parseObject(text).get("ID");
                if (null != id) {
                    pid = id.toString().trim();
                }
                String state = JSON.parseObject(text).get("State").toString().trim();
                log.info("{}  state : {} ", id, state);
                if (state.equals("5")) {
                    progessMap.put(pid, "stop");
                    RedisPool.getJedis().hmset(NettyUtils.TBOMMAP, progessMap);
                    RedisPool.getJedis().close();
                    log.info("??????< {} >????????????", pid);
                }
                break;
            }
            case 3: {//????????????
                log.info(" ???????????????????????????@ {}", LocalDateTime.now());
                //???json?????????????????? key???id,value????????????
                Map mapData = JSON.parseObject(text);
                String key = mapData.get("ID").toString().trim();//??????ID
                String progress = mapData.get("Progress").toString().trim();//??????
                progessMap.put(key, progress);
                RedisPool.getJedis().hmset(NettyUtils.TBOMMAP, progessMap);
                RedisPool.getJedis().close();
                log.info("key = {} , Progress = {} ", key, progress);
                break;
            }
            case 4: {//????????????
                //???????????????????????????????????????ID????????????????????????.
                try {
                    byte[] bytes = msg.getContent();
                    String s = new String(bytes);
                    Object rid = JSON.parseObject(s).get("ID");
                    String id = "000000";
                    if (rid != null) {
                        id = rid.toString().trim();
                    }
                    log.debug("get file id is = {} ", id);
                    String suffix = "docx";
                    Object suffixObj = JSON.parseObject(s).get("Suffix");
                    if (null != suffixObj) {
                        suffix = suffixObj.toString().trim();
                    }
                    log.debug("get file suffix is = {} ", suffix);
                    String report = "";
                    Object reportObj = JSON.parseObject(s).get("Report");
                    if (null != reportObj) {
                        report = reportObj.toString().trim();
                    }
                    log.info("get file binary is = {}", report);
                    byte[] bytes1 = NettyUtils.hexStringToBytes(report);
                    String reportPath = RedisPool.getJedis().get(NettyUtils.REPORTPATH) + File.separator;
                    RedisPool.getJedis().close();
                    log.debug("report save path ==> {}", reportPath);
                    Path path = Paths.get(reportPath + File.separator, NettyUtils.hexStringToInt(id) + "_" + System.currentTimeMillis() + "." + suffix);
                    if (!Files.exists(path)) {
                        //??????????????????,???????????????.
                        try {
                            Files.createFile(path);
                        } catch (Exception ex) {

                        }
                    }
                    Path p = Files.write(path, bytes1, StandardOpenOption.CREATE);
                    log.info("{} files recv success,@ {} ", p.toString(), LocalDateTime.now());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                break;
            }
            case 5: { //??????
                Object id = null;
                String key = "";
                try {
                    id = JSON.parseObject(text).get("ID");
                } catch (Exception ex) {
                    id = "0";
                }
                if (id != null) {
                    key = id.toString().trim();
                }
                String bid = "";
                Object bid1 = JSON.parseObject(text).get("BID");
                if (null != bid1) {
                    bid = bid1.toString().trim();
                }
                String code1 = JSON.parseObject(text).getString("Code");
                String code = "";
                if (null != code1) {
                    code = code1;
                }
                Object message = null;
                String errMsg = "";
                try {
                    message = JSON.parseObject(text).get("Message");
                } catch (Exception ex) {
                    message = "";
                }
                if (message != null) {
                    errMsg = message.toString().trim();
                }
                if ("1".equals(bid)) {//??????TBOM??????
                    if (code.equals("1")) {//??????
                        log.error("client {}  load tbom error : {}  @  {}", ctx.channel().remoteAddress(), errMsg, LocalDateTime.now());
                        log.debug("TODO resend tbom list");

                        String poll = RedisPool.getJedis().get(NettyUtils.TBOMLIST);
                        RedisPool.getJedis().close();
                        if (null != poll) {
                            ctx.writeAndFlush(NettyUtils.createLoadTBOM(poll.getBytes()));
                        }
                    }
                    if (code.equals("0")) {//
                        log.info("client recv tobm list success,@ " + LocalDateTime.now());
                        // onlytest_perform_tbom()
//                        onlytest(ctx);
                    }
                    //0,?????????1,?????????2????????????3????????????4????????????
                } else if ("2".equals(bid)) {//??????????????????
                    //???json?????????????????? key???id,value????????????
                    String state = "0";
                    if (code.equals("0")) {
                        state = "1";//????????????
                        progessMap.put(key, "started");
                        RedisPool.getJedis().hmset(NettyUtils.TBOMMAP, progessMap);
                        RedisPool.getJedis().close();
                        log.info(" {} start sucess", key);
                    } else if (code.equals("1")) {//????????????
                        state = "2";
                        log.error("{} start error : {}", key, errMsg);
                    }

                    if (code.equals("1")) {//??????
                        log.error("client {}  load tbom error : {} @ {}", ctx.channel().remoteAddress(), errMsg, LocalDateTime.now());
                        //TODO ????????????????????????.
                    }
                    if (code.equals("0")) {//
                    }
                    //0,?????????1,?????????2????????????3????????????4????????????
                } else {
                    log.info("bid is = ", bid);
                }
                break;
            }
            default:
                //????????????????????????.
                log.warn("unsupported protocol {} ", businessCode);
                break;
        }
    }

    private void onlytest(ChannelHandlerContext ctx) {
        log.debug("?????????????????????TBOM??????");
        String performTbom = "{\"ID\":\"00000000-0000-0000-0000-000000000010\",\n" +
                "\"State\":\"1\",\n" +
                "}";
        TBOMProtocol performTBOM = NettyUtils.createPerformTBOM(performTbom);
        ctx.writeAndFlush(performTBOM);
        log.debug("?????????????????????TBOM?????? ??????");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        cause.printStackTrace();
        log.error(cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        activeChannelMap.put("tbom", channel);
        //???channel ??????????????????.
        //?????????????????????????????????
        log.info("?????????< {} " + " >?????????????????????[ {}" + " ]", channel.remoteAddress(), channel.localAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.warn("?????????  < {} >  ?????? ??????,@ {}", ctx.channel().remoteAddress(), LocalDateTime.now());
        ctx.fireChannelInactive();
    }
}
