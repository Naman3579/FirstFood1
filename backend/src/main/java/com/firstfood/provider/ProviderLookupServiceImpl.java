package com.firstfood.provider;

import com.firstfood.provideraccess.ProviderNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderLookupServiceImpl implements ProviderLookupService {

    /** One degree of latitude is about 111 km everywhere. */
    private static final double KM_PER_DEGREE = 111.0;

    private final FoodProviderRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public ProviderLookupServiceImpl(FoodProviderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProviderIntake lockForIntake(UUID providerId) {
        FoodProvider provider = repository.findByIdForUpdate(providerId).orElseThrow(ProviderNotFoundException::new);
        return new ProviderIntake(provider.getStatus(), provider.isAcceptingNewCustomers(),
                provider.getMaxActiveSubscriptions());
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderIntake intakeOf(UUID providerId) {
        FoodProvider provider = repository.findById(providerId).orElseThrow(ProviderNotFoundException::new);
        return new ProviderIntake(provider.getStatus(), provider.isAcceptingNewCustomers(),
                provider.getMaxActiveSubscriptions());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findNames(Collection<UUID> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(providerIds).stream()
                .collect(Collectors.toMap(FoodProvider::getId, FoodProvider::getName));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderListing> search(ProviderSearchFilter f) {
        // Built from fixed fragments only; every value travels as a bound parameter.
        StringBuilder jpql = new StringBuilder("select p from FoodProvider p where p.status <> :closed");
        Map<String, Object> params = new HashMap<>();
        params.put("closed", ProviderStatus.CLOSED);

        if (f.availableOnly()) {
            jpql.append(" and p.status = :active and p.acceptingNewCustomers = true");
            params.put("active", ProviderStatus.ACTIVE);
        }
        if (f.types() != null && !f.types().isEmpty()) {
            jpql.append(" and p.id in (select q.id from FoodProvider q join q.providerTypes t where t in :types)");
            params.put("types", f.types());
        }
        if (f.foodTypes() != null && !f.foodTypes().isEmpty()) {
            jpql.append(" and p.foodType in :foodTypes");
            params.put("foodTypes", f.foodTypes());
        }
        if (f.query() != null && !f.query().isBlank()) {
            jpql.append(" and (lower(p.name) like :q escape '!' or lower(p.locality) like :q escape '!'"
                    + " or lower(p.city) like :q escape '!')");
            params.put("q", "%" + escapeLike(f.query().trim().toLowerCase(Locale.ROOT)) + "%");
        }

        boolean hasCity = f.city() != null && !f.city().isBlank();
        if (hasCity) {
            params.put("city", f.city().trim().toLowerCase(Locale.ROOT));
        }
        if (f.hasLocation()) {
            double dLat = f.radiusKm() / KM_PER_DEGREE;
            double cos = Math.max(Math.cos(Math.toRadians(f.latitude())), 0.01);
            double dLng = f.radiusKm() / (KM_PER_DEGREE * cos);
            jpql.append(" and ((p.latitude between :minLat and :maxLat");
            params.put("minLat", BigDecimal.valueOf(f.latitude() - dLat));
            params.put("maxLat", BigDecimal.valueOf(f.latitude() + dLat));
            if (dLng < 180) {
                jpql.append(" and p.longitude between :minLng and :maxLng");
                params.put("minLng", BigDecimal.valueOf(f.longitude() - dLng));
                params.put("maxLng", BigDecimal.valueOf(f.longitude() + dLng));
            }
            jpql.append(")");
            if (hasCity) {
                // A provider with no pinned location is still found by its city; it just has no distance.
                jpql.append(" or (p.latitude is null and lower(p.city) = :city)");
            }
            jpql.append(")");
        } else if (hasCity) {
            jpql.append(" and lower(p.city) = :city");
        }
        jpql.append(" order by p.createdAt desc");

        TypedQuery<FoodProvider> query = entityManager.createQuery(jpql.toString(), FoodProvider.class);
        params.forEach(query::setParameter);
        query.setMaxResults(f.maxCandidates());
        return query.getResultList().stream().map(ProviderListing::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderListing> findListing(UUID providerId) {
        return repository.findById(providerId)
                .filter(p -> p.getStatus() != ProviderStatus.CLOSED)
                .map(ProviderListing::from);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
