package com.localbuddy.experience;

import com.localbuddy.common.exception.BadRequestException;
import com.localbuddy.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CityService {

    private final CityRepository cityRepository;

    public CityService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    @Transactional(readOnly = true)
    public List<CityResponse> getActiveCities() {
        return cityRepository.findByActiveTrueOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CityResponse> getAllCities() {
        return cityRepository.findAllByOrderByDisplayOrderAscNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CityResponse createCity(CreateCityRequest request) {
        String name = request.name().trim();
        String country = request.country().trim();

        if (cityRepository.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("A city with this name already exists");
        }

        City city = new City();
        city.setName(name);
        city.setSlug(generateUniqueSlug(name));
        city.setCountry(country);
        city.setLatitude(request.latitude());
        city.setLongitude(request.longitude());
        city.setActive(true);
        city.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);

        return toResponse(cityRepository.save(city));
    }

    @Transactional
    public CityResponse setCityActive(UUID cityId, boolean active) {
        City city = cityRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));

        city.setActive(active);

        return toResponse(cityRepository.save(city));
    }

    private CityResponse toResponse(City city) {
        return new CityResponse(
                city.getId(),
                city.getName(),
                city.getSlug(),
                city.getCountry(),
                city.getLatitude(),
                city.getLongitude(),
                city.isActive(),
                city.getDisplayOrder()
        );
    }

    private String generateUniqueSlug(String name) {
        String baseSlug = slugify(name);
        String candidate = baseSlug;
        int counter = 1;

        while (cityRepository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + counter;
            counter++;
        }

        return candidate;
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return normalized
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
