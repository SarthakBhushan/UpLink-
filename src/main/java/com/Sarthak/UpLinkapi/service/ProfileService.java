package com.Sarthak.UpLinkapi.service;

import com.Sarthak.UpLinkapi.documents.ProfileDocument;
import com.Sarthak.UpLinkapi.dto.ProfileDTO;
import com.Sarthak.UpLinkapi.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;

import static org.springframework.security.core.context.SecurityContextHolder.getContext;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {
    private final ProfileRepository profileRepository;

    public ProfileDTO createProfile(ProfileDTO profileDTO){

        if(profileRepository.existsByClerkId(profileDTO.getClerkId())){
            return updateProfile(profileDTO);
        }

        String email = (profileDTO.getEmail() != null && !profileDTO.getEmail().isBlank()) 
                ? profileDTO.getEmail().trim() 
                : null;

        ProfileDocument profile = ProfileDocument.builder()
                .clerkId(profileDTO.getClerkId())
                .email(email)
                .firstName(profileDTO.getFirstName())
                .lastName((profileDTO.getLastName()))
                .photoUrl(profileDTO.getPhotoUrl())
                .credits(5)
                .createdAt(Instant.now())
                .build();

        profile = profileRepository.save(profile);

        return ProfileDTO.builder()
                .id(profile.getId())
                .clerkId(profile.getClerkId())
                .email(profile.getEmail())
                .firstName(profile.getFirstName())
                .lastName((profile.getLastName()))
                .photoUrl(profile.getPhotoUrl())
                .credits(profile.getCredits())
                .createdAt(profile.getCreatedAt())
                .build();
    }

    public ProfileDTO updateProfile(ProfileDTO profileDTO){
        ProfileDocument existingProfile = profileRepository.findByClerkId(profileDTO.getClerkId());
        if(existingProfile!=null) {
            if(profileDTO.getEmail()!=null && !profileDTO.getEmail().isBlank()){
                existingProfile.setEmail(profileDTO.getEmail().trim());
            }
            if(profileDTO.getFirstName()!=null && !profileDTO.getFirstName().isEmpty()){
                existingProfile.setFirstName(profileDTO.getFirstName());
            }
            if(profileDTO.getLastName()!=null && !profileDTO.getLastName().isEmpty()){
                existingProfile.setLastName(profileDTO.getLastName());
            }
            if(profileDTO.getPhotoUrl()!=null && !profileDTO.getPhotoUrl().isEmpty()){
                existingProfile.setPhotoUrl(profileDTO.getPhotoUrl());
            }
            profileRepository.save(existingProfile);

            return ProfileDTO.builder()
                    .id(existingProfile.getId())
                    .email(existingProfile.getEmail())
                    .clerkId(existingProfile.getClerkId())
                    .firstName(existingProfile.getFirstName())
                    .lastName(existingProfile.getLastName())
                    .credits(existingProfile.getCredits())
                    .createdAt(existingProfile.getCreatedAt())
                    .photoUrl(existingProfile.getPhotoUrl())
                    .build();
        }
        return null;
    }

    public boolean existsByClerkId(String clerkId){
        return profileRepository.existsByClerkId(clerkId);
    }

    public void deleteProfile(String clerkId){
        ProfileDocument existingProfile = profileRepository.findByClerkId(clerkId);
        if(existingProfile!=null){
            profileRepository.delete(existingProfile);
        }
    }

    /**
     * Retrieves the current authenticated user's profile.
     * If the profile does not yet exist in MongoDB (e.g. localhost development where Clerk webhooks cannot reach),
     * auto-provisions a baseline profile on first access to prevent NullPointerExceptions across the platform.
     * Stores email as null so unique sparse index prevents duplicate key collisions.
     */
    public ProfileDocument getCurrentProfile() {
        if (getContext().getAuthentication() == null) {
            throw new UsernameNotFoundException("User not authenticated");
        }
        String clerkId = SecurityContextHolder.getContext().getAuthentication().getName();
        if (clerkId == null || clerkId.isBlank()) {
            throw new UsernameNotFoundException("Unidentified Clerk authentication principal");
        }

        ProfileDocument profile = profileRepository.findByClerkId(clerkId);
        if (profile == null) {
            log.info("Profile not found in database for clerkId: {}. Auto-provisioning initial profile.", clerkId);
            profile = ProfileDocument.builder()
                    .clerkId(clerkId)
                    .email(null) // null avoids unique index collisions on sparse email index
                    .firstName("User")
                    .lastName("")
                    .credits(5)
                    .createdAt(Instant.now())
                    .build();
            profile = profileRepository.save(profile);
        }
        return profile;
    }
}
