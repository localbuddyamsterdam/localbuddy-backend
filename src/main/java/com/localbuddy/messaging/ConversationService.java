package com.localbuddy.messaging;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
import com.localbuddy.notification.NotificationService;
import com.localbuddy.notification.NotificationType;
import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import com.localbuddy.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final MessageRepository messageRepository;
    private final ExperienceRepository experienceRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationParticipantRepository participantRepository,
                               MessageRepository messageRepository,
                               ExperienceRepository experienceRepository,
                               UserRepository userRepository,
                               NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    // ---------------------------------------------------------------- customer / host

    /** Start (or reuse) the customer's conversation with the host of an experience. */
    @Transactional
    public ConversationResponse startConversation(UUID currentUserId, StartConversationRequest request) {
        Experience experience = experienceRepository.findWithLocalProfileAndUserById(request.experienceId())
                .orElseThrow(() -> new ResourceNotFoundException("Experience not found"));

        User host = experience.getLocalProfile().getUser();
        if (host.getId().equals(currentUserId)) {
            throw new BadRequestException("You cannot start a conversation with yourself");
        }

        return conversationRepository
                .findCustomerHostConversation(currentUserId, host.getId(), experience.getId())
                .map(existing -> toResponse(existing, currentUserId))
                .orElseGet(() -> {
                    User customer = loadUser(currentUserId);
                    Conversation conversation = new Conversation();
                    conversation.setType(ConversationType.CUSTOMER_HOST);
                    conversation.setExperience(experience);
                    conversation = conversationRepository.save(conversation);
                    addParticipant(conversation, customer, ParticipantRole.CUSTOMER);
                    addParticipant(conversation, host, ParticipantRole.HOST);
                    return toResponse(conversation, currentUserId);
                });
    }

    @Transactional
    public MessageResponse sendMessage(UUID currentUserId, UUID conversationId, SendMessageRequest request) {
        ConversationParticipant participant = requireParticipant(conversationId, currentUserId);
        Message message = postMessage(participant.getConversation(), participant.getUser(),
                request.body(), participant.getRole());
        return toMessageResponse(message);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getMyConversations(UUID currentUserId) {
        return conversationRepository.findMyConversations(currentUserId).stream()
                .map(conversation -> toResponse(conversation, currentUserId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> getMessages(UUID currentUserId, UUID conversationId) {
        requireParticipant(conversationId, currentUserId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional
    public void markRead(UUID currentUserId, UUID conversationId) {
        ConversationParticipant participant = requireParticipant(conversationId, currentUserId);
        participant.setLastReadAt(Instant.now());
        participantRepository.save(participant);
    }

    // ---------------------------------------------------------------- admin

    /** Admin monitoring: every conversation, newest activity first. */
    @Transactional(readOnly = true)
    public List<ConversationResponse> adminListConversations() {
        return conversationRepository.findAllByActivity().stream()
                .map(conversation -> toResponse(conversation, null))
                .toList();
    }

    /** Admin monitoring: read any conversation's messages without being a member. */
    @Transactional(readOnly = true)
    public List<MessageResponse> adminGetMessages(UUID conversationId) {
        requireConversation(conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /** Admin takes over / responds in any conversation. Joins as an ADMIN participant on first reply. */
    @Transactional
    public MessageResponse adminReply(UUID adminUserId, UUID conversationId, SendMessageRequest request) {
        Conversation conversation = requireConversation(conversationId);
        User admin = loadUser(adminUserId);
        if (!participantRepository.existsByConversationIdAndUserId(conversationId, adminUserId)) {
            addParticipant(conversation, admin, ParticipantRole.ADMIN);
        }
        Message message = postMessage(conversation, admin, request.body(), ParticipantRole.ADMIN);
        return toMessageResponse(message);
    }

    /** Admin starts a private side conversation with a single customer or host. */
    @Transactional
    public ConversationResponse adminStartSideConversation(UUID adminUserId,
                                                           AdminStartSideConversationRequest request) {
        User admin = loadUser(adminUserId);
        User target = userRepository.findById(request.targetUserId())
                .orElseThrow(() -> new BadRequestException("Target user not found"));

        ParticipantRole targetRole;
        ConversationType type;
        if (target.getRole() == UserRole.LOGGED_IN_USER) {
            targetRole = ParticipantRole.CUSTOMER;
            type = ConversationType.ADMIN_CUSTOMER;
        } else if (target.getRole() == UserRole.LOCAL) {
            targetRole = ParticipantRole.HOST;
            type = ConversationType.ADMIN_HOST;
        } else {
            throw new BadRequestException("Side conversations can only target a customer or a host");
        }

        Conversation conversation = new Conversation();
        conversation.setType(type);
        conversation.setSubject(trimToNull(request.subject()));
        if (request.experienceId() != null) {
            Experience experience = experienceRepository.findById(request.experienceId())
                    .orElseThrow(() -> new BadRequestException("Invalid experience"));
            conversation.setExperience(experience);
        }
        conversation = conversationRepository.save(conversation);
        addParticipant(conversation, admin, ParticipantRole.ADMIN);
        addParticipant(conversation, target, targetRole);

        String body = trimToNull(request.body());
        if (body != null) {
            postMessage(conversation, admin, body, ParticipantRole.ADMIN);
        }
        return toResponse(conversation, adminUserId);
    }

    // ---------------------------------------------------------------- helpers

    private Message postMessage(Conversation conversation, User sender, String body, ParticipantRole role) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setSenderUser(sender);
        message.setSenderRole(role);
        message.setBody(body.trim());
        Message saved = messageRepository.save(message);

        conversation.setLastMessageAt(Instant.now());
        conversationRepository.save(conversation);

        notifyOtherParticipants(conversation, sender, saved);
        return saved;
    }

    /** Pings every other participant (in-app) when a new message arrives. */
    private void notifyOtherParticipants(Conversation conversation, User sender, Message message) {
        String senderLabel = labelFor(message.getSenderRole(), sender.getFullName());
        String body = message.getBody();
        String preview = body != null && body.length() > 140 ? body.substring(0, 140) + "…" : body;

        for (ConversationParticipant p : participantRepository.findByConversationId(conversation.getId())) {
            if (p.getUser().getId().equals(sender.getId())) {
                continue;
            }
            notificationService.createInAppNotificationForUser(
                    p.getUser(),
                    NotificationType.NEW_MESSAGE,
                    "New message from " + senderLabel,
                    senderLabel + ": " + (preview == null ? "" : preview),
                    "CONVERSATION",
                    conversation.getId(),
                    "NEW_MESSAGE:" + message.getId() + ":" + p.getUser().getId()
            );
        }
    }

    private ConversationParticipant requireParticipant(UUID conversationId, UUID userId) {
        return participantRepository.findByConversationIdAndUserId(conversationId, userId)
                // Hide existence from non-members.
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    private void addParticipant(Conversation conversation, User user, ParticipantRole role) {
        ConversationParticipant participant = new ConversationParticipant();
        participant.setConversation(conversation);
        participant.setUser(user);
        participant.setRole(role);
        participantRepository.save(participant);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("Invalid user"));
    }

    /** "Admin (Sarah Chen)" for admins; the plain name otherwise. */
    private String labelFor(ParticipantRole role, String name) {
        String safeName = (name == null || name.isBlank()) ? "Someone" : name;
        return role == ParticipantRole.ADMIN ? "Admin (" + safeName + ")" : safeName;
    }

    private ConversationResponse toResponse(Conversation conversation, UUID viewerUserId) {
        List<ConversationParticipant> participants =
                participantRepository.findByConversationId(conversation.getId());

        List<ConversationResponse.Participant> participantViews = participants.stream()
                .map(p -> new ConversationResponse.Participant(
                        p.getUser().getId(), p.getUser().getFullName(), p.getRole()))
                .toList();

        Instant since = participants.stream()
                .filter(p -> p.getUser().getId().equals(viewerUserId))
                .findFirst()
                .map(ConversationParticipant::getLastReadAt)
                .orElse(null);
        boolean filterSince = since != null;
        long unread = viewerUserId == null ? 0
                : messageRepository.countUnread(conversation.getId(), viewerUserId, since, filterSince);

        return new ConversationResponse(
                conversation.getId(),
                conversation.getType(),
                conversation.getExperience() != null ? conversation.getExperience().getId() : null,
                conversation.getBooking() != null ? conversation.getBooking().getId() : null,
                conversation.getSubject(),
                participantViews,
                conversation.getLastMessageAt(),
                unread,
                conversation.getCreatedAt()
        );
    }

    private MessageResponse toMessageResponse(Message message) {
        User sender = message.getSenderUser();
        String name = sender.getFullName();
        ParticipantRole role = message.getSenderRole();
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                sender.getId(),
                name,
                role,
                labelFor(role, name),
                message.getBody(),
                message.getCreatedAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
