package bank.cardissuing.thirdparty.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThirdPartyContractRepository extends JpaRepository<ThirdPartyContract, Long> {
    Optional<ThirdPartyContract> findByProviderKey(String providerKey);
}
