package com.example.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class WebSocketHandler extends TextWebSocketHandler {
    private List<WebSocketSession> waitingClients = new CopyOnWriteArrayList<>();   //매칭 대기중
    private Map<WebSocketSession, String> matchedClients = new ConcurrentHashMap<>();   //매칭이 되면, <클라이언트, 방KEY>값이 들어감
    private List<String> pendingMessages = new ArrayList<>();   //받은 메세지 저장
    private List<String> pendingMessageskey = new ArrayList<>();    //방KEY값 저장
    private Map<String, WebSocketSession> clientSessions = new ConcurrentHashMap<>();   //사용자 ID값 저장

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);   //로그값 보여주는함수

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 클라이언트가 연결되면 대기 목록에 추가합니다.
        waitingClients.add(session);

        // 클라이언트로부터 전송된 아이디를 추출합니다.
        String clientId = getClientIdFromSession(session);
        if (clientId != null) {
            clientSessions.put(clientId, session);
            logger.info("Client connected with ID: " + clientId);

            // 일정 수의 클라이언트가 대기 중일 때, 매칭을 시작합니다.
            if (waitingClients.size() >= 2) {
                matchClients();
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 클라이언트로부터 받은 메시지 처리
        String receivedMessage = message.getPayload();  //받은 메세지
        String clientId = getClientIdFromSession(session);  //메세지를 보낸 클라이언트의 ID값

        if (clientId != null) { // 메시지 형식: 공격력-스피드-마나-체력-상대ID
            logger.info("Received message from client with ID {}: {}", clientId, receivedMessage);
            pendingMessages.add(clientId + "-" + receivedMessage);  //메세지를 보낸 클라이언트의 ID - 받은 메세지 형식
            pendingMessageskey.add(matchedClients.get(session));    //메세지를 보낸 클라이언트가 들어가 있는 방의 KEY를 찾아 넣어줌

            if (pendingMessages.size() >= 2) {  //저장돼 있는 메세지가 2개 이상일경우
                int savesize = pendingMessages.size();  //아래에서 삭제연산이 들어가 미리 사이즈 저장

                for (int i = savesize - 1; i > 0; i--) {    //마지막에 들어온 메세지의 방KEY값이 같은 메세지가 있는가 (매칭중인 상대에게만 반응)
                    if (Objects.equals(pendingMessageskey.get(savesize - 1), pendingMessageskey.get(i - 1))) {

                        String firstMessage = pendingMessages.get(i - 1);
                        String secondMessage = pendingMessages.get(savesize - 1);

                        //pass일경우 보낸사람id - pass - 자기체력
                        String[] firstParts = firstMessage.split("-");  // 보낸사람id - 공격력 - 스피드 - 마나 - 체력 - 상대id - 타입 - 스킬 아이디
                        String[] secondParts = secondMessage.split("-");    // 보낸사람id - 공격력 - 스피드 - 마나 - 체력 - 상대id - 타입 - 스킬 아이디


                        if (firstParts[1].equals("pass") && secondParts[1].equals("pass")) {    //둘다 pass 일경우
                            sendMessageToMatchedClients(firstParts[0], "Skip");
                            sendMessageToMatchedClients(secondParts[0], "Skip");

                            // 처리된 메시지 삭제
                            pendingMessages.remove(i - 1);
                            pendingMessages.remove(savesize - 2);
                            pendingMessageskey.remove(i - 1);
                            pendingMessageskey.remove(savesize - 2);

                            break;
                        }
                        else if (!firstParts[1].equals("pass") && secondParts[1].equals("pass")) {    //방금 들어온 메세지가 pass이고, 그 이전에 들어왔던 메세지는 pass가 아닐경우
                            String responseMessage = secondParts[0] + "/" + firstParts[0] + "-" + firstParts[3] +  "-"  + firstParts[1] +  "-" +firstParts[6] + "-" + firstParts[7];
                            sendMessageToMatchedClients(firstParts[0], "OneSkip " + responseMessage);
                            sendMessageToMatchedClients(secondParts[0], "OneSkip " + responseMessage);

                            // 처리된 메시지 삭제
                            pendingMessages.remove(i - 1);
                            pendingMessages.remove(savesize - 2);
                            pendingMessageskey.remove(i - 1);
                            pendingMessageskey.remove(savesize - 2);

                            break;
                        }
                        else if (firstParts[1].equals("pass")) {  //방금 들어온 메세지가 pass가 아니고,(위에서 if문으로 체크함) 그 이전에 들어왔던 메세지가 pass일경우
                            String responseMessage = firstParts[0] + "/" + secondParts[0] + "-" + secondParts[3] + "-" + secondParts[1] +  "-" + secondParts[6] + "-" +secondParts[7];
                            sendMessageToMatchedClients(firstParts[0], "OneSkip " + responseMessage);
                            sendMessageToMatchedClients(secondParts[0], "OneSkip " + responseMessage);

                            // 처리된 메시지 삭제
                            pendingMessages.remove(i - 1);
                            pendingMessages.remove(savesize - 2);
                            pendingMessageskey.remove(i - 1);
                            pendingMessageskey.remove(savesize - 2);

                            break;
                        } else{ // 모두 전투 상황일때
                            // 보낸사람id - 공격력 - 스피드 - 마나 - 체력 - 상대id - 타입

                            String realmessage;
                            Random rd = new Random();
                            //스피드 비교
                            if (Integer.parseInt(firstParts[2]) > Integer.parseInt(secondParts[2]) ||
                                    ((Integer.parseInt(firstParts[2]) == Integer.parseInt(secondParts[2])) && (rd.nextInt(2) + 1) % 2 == 1 )){
                                //firstParts가 우선 타격 시
                                String responseMessage1 = secondParts[0] + "-" +  secondParts[3] + "-" + secondParts[1] + "-" +secondParts[6]+"-"+secondParts[7];
                                String responseMessage2 = firstParts[0] + "-"  + firstParts[3] + "-" + firstParts[1] + "-" +firstParts[6]+"-"+firstParts[7];

                                realmessage = responseMessage2 + "/" + responseMessage1;
                            } else{

                                String responseMessage1 = secondParts[0] + "-" +  secondParts[3] + "-" + secondParts[1] + "-" +secondParts[6]+"-"+secondParts[7];
                                String responseMessage2 = firstParts[0] + "-"  + firstParts[3] + "-" + firstParts[1] + "-" +firstParts[6]+"-"+ firstParts[7];

                                realmessage = responseMessage1 + "/" + responseMessage2;
                            }

                            // 클라이언트에게 응답 전송
                            sendMessageToMatchedClients(firstParts[0], "fight " + realmessage);
                            sendMessageToMatchedClients(secondParts[0], "fight " + realmessage);

                            // 처리된 메시지 삭제
                            pendingMessages.remove(i - 1);
                            pendingMessages.remove(savesize - 2);
                            pendingMessageskey.remove(i - 1);
                            pendingMessageskey.remove(savesize - 2);

                            break;
                        }



                    }
                }


            }


        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 클라이언트 연결이 종료되면 대기 목록에서 제거합니다.
        waitingClients.remove(session);
        // 클라이언트와 매칭된 상대방을 찾습니다.
        String matchingKey = matchedClients.get(session);
        WebSocketSession opponentSession = null;
        for (Map.Entry<WebSocketSession, String> entry : matchedClients.entrySet()) {
            if (entry.getValue().equals(matchingKey) && entry.getKey() != session) {
                opponentSession = entry.getKey();
                break;
            }
        }

        if (opponentSession != null) {
            try {
                // 상대방에 "You Win" 메시지를 전송합니다.
                opponentSession.sendMessage(new TextMessage("Opponent Disconnected"));
            } catch (IOException e) {
                logger.error("Error sending 'You Win' message to the opponent.", e);
            }
        }
        matchedClients.remove(session);

        // 클라이언트로부터 전송된 아이디를 추출합니다.
        String clientId = getClientIdFromSession(session);
        if (clientId != null) {
            logger.info("Client disconnected with ID: " + clientId);
        }
    }

    private String getClientIdFromSession(WebSocketSession session) {
        // WebSocket 연결 시 클라이언트가 전송한 아이디를 추출합니다.
        String query = session.getUri().getQuery();
        if (query != null) {
            String[] queryParts = query.split("="); //~~~..=(나의ID)로 클라이언트가 연결되는데, =이후의 올값이 클라이언트 ID
            if (queryParts.length > 1) {
                return queryParts[1];
            }
        }
        return null;
    }

    private void matchClients() {
        // 두 개의 클라이언트를 매칭하고 게임을 시작합니다.
        if (waitingClients.size() >= 2) {   //waitingClients에 2명이 있다면,
            WebSocketSession client1 = waitingClients.remove(0);    //waitingClients에서 제거
            WebSocketSession client2 = waitingClients.remove(0);    //waitingClients에서 제거

            String matchingkey = generateUniqueId();    //방 랜덤KEY 생성
            matchedClients.put(client1, matchingkey);   //matchedClients에 클라이언트1, 방KEY 입력
            matchedClients.put(client2, matchingkey);   //matchedClients에 클라이언트2, 방KEY 입력

            logger.info("Matching clients: {} and {}", getClientIdFromSession(client1), getClientIdFromSession(client2));
            startGame(client1, client2);


            // 상대 ID를 전송
            sendMessageToMatchedClients(getClientIdFromSession(client1), "Enemy ID = " + getClientIdFromSession(client2));
            sendMessageToMatchedClients(getClientIdFromSession(client2), "Enemy ID = " + getClientIdFromSession(client1));
        }
    }

    private void sendMessageToMatchedClients(String clientId, String message) {
        // clientId에게 message 전송
        WebSocketSession session = clientSessions.get(clientId);
        if (session != null) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void startGame(WebSocketSession client1, WebSocketSession client2) {
        // 게임 시작 로직
        logger.info("Starting the game between {} and {}", getClientIdFromSession(client1), getClientIdFromSession(client2));

        // 게임 시작 메시지 전송
        for (WebSocketSession session : List.of(client1, client2)) {
            try {
                session.sendMessage(new TextMessage("Game Start"));
            } catch (IOException e) {
                logger.error("Error sending game start message to a client.", e);
            }
        }
    }

    private String generateUniqueId() {
        return UUID.randomUUID().toString();
    }

}