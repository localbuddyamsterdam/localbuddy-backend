package com.localbuddy.messaging;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import com.localbuddy.experience.Experience;
import com.localbuddy.experience.ExperienceRepository;
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

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ExperienceRepository experienceRepository,
                               UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
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
                .findByTravelerUserIdAndHostUserIdAndExperienceId(traveler.getId(), host.getId(), experience.getId())
                .orElseGet(() -> {
                    Conversation created = new Conversation();
                    created.setTravelerUser(traveler);
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

        return toMessageResponse(saved);
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

        boolean isMember = conversation.getTravelerUser().getId().equals(currentUserId)
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
                conversation.getTravelerUser().getId(),
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
