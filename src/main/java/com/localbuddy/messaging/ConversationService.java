package com.localbuddy.messaging;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ExperienceRepository experienceRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ExperienceRepository experienceRepository,
                               UserRepository userRepository,
                               NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /** Start (or reuse) the traveler's conversation with the host of an experience. */
    @Transactional
    public ConversationResponse startConversation(UUID currentUserId, StartConversationRequest request) {
        Experience experience = experienceRepository.findWithLocalProfileAndUserById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        User host = experience.getLocalProfile().getUser();
        if (host.getId().equals(currentUserId)) {
            throw new BadRequestException("You cannot start a conversation with yourself");
        }

        User traveler = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        Conversation conversation = conversationRepository
                .findByLoggedInUserIdAndHostUserIdAndExperienceId(traveler.getId(), host.getId(), experience.getId())
                .orElseGet(() -> {
                    Conversation created = new Conversation();
                    created.setLoggedInUser(traveler);
                    created.setHostUser(host);
                    created.setExperience(experience);
                    return conversationRepository.save(created);
                });

        return toResponse(conversation, currentUserId);
    }

    @Transactional
    public MessageResponse sendMessage(UUID currentUserId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = getMemberConversation(conversationId, currentUserId);

        User sender = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));

        Message message = new Message();
        message.setConversation(conversation);
        message.setSenderUser(sender);
        message.setBody(request.body().trim());
        Message saved = messageRepository.save(message);

        conversation.setLastMessageAt(Instant.now());
        conversationRepository.save(conversation);

        notifyRecipient(conversation, sender, saved);

        return toMessageResponse(saved);
    }

    /** Pings the other party (in-app) when they receive a new message. */
    private void notifyRecipient(Conversation conversation, User sender, Message message) {
        User recipient = conversation.getLoggedInUser().getId().equals(sender.getId())
                ? conversation.getHostUser()
                : conversation.getLoggedInUser();

        String senderName = sender.getFullName() != null ? sender.getFullName() : "Someone";
        String body = message.getBody();
        String preview = body != null && body.length() > 140 ? body.substring(0, 140) + "…" : body;

        notificationService.createInAppNotificationForUser(
                recipient,
                NotificationType.NEW_MESSAGE,
                "New message from " + senderName,
                senderName + ": " + (preview == null ? "" : preview),
                "CONVERSATION",
                conversation.getId(),
                "NEW_MESSAGE:" + message.getId()
        );
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getMyConversations(UUID currentUserId) {
        return conversationRepository.findMyConversations(currentUserId).stream()
                .map(conversation -> toResponse(conversation, currentUserId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getMessages(UUID currentUserId, UUID conversationId) {
        getMemberConversation(conversationId, currentUserId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional
    public void markRead(UUID currentUserId, UUID conversationId) {
        getMemberConversation(conversationId, currentUserId);
        messageRepository.markReadForRecipient(conversationId, currentUserId, Instant.now());
    }

    private Conversation getMemberConversation(UUID conversationId, UUID currentUserId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        boolean isMember = conversation.getLoggedInUser().getId().equals(currentUserId)
                || conversation.getHostUser().getId().equals(currentUserId);
        if (!isMember) {
            // Hide existence from non-members.
            throw new ResourceNotFoundException("Conversation not found");
        }
        return conversation;
    }

    private ConversationResponse toResponse(Conversation conversation, UUID currentUserId) {
        long unread = messageRepository.countByConversationIdAndSenderUserIdNotAndReadAtIsNull(
                conversation.getId(), currentUserId);

        return new ConversationResponse(
                conversation.getId(),
                conversation.getLoggedInUser().getId(),
                conversation.getHostUser().getId(),
                conversation.getExperience() != null ? conversation.getExperience().getId() : null,
                conversation.getBooking() != null ? conversation.getBooking().getId() : null,
                conversation.getLastMessageAt(),
                unread,
                conversation.getCreatedAt()
        );
    }

    private MessageResponse toMessageResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getSenderUser().getId(),
                message.getBody(),
                message.getReadAt(),
                message.getCreatedAt()
        );
    }
}
