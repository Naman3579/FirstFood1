package com.firstfood.membership;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonServiceImpl implements PersonService {

    private final PersonRepository personRepository;
    private final PersonProvisioner provisioner;

    public PersonServiceImpl(PersonRepository personRepository, PersonProvisioner provisioner) {
        this.personRepository = personRepository;
        this.provisioner = provisioner;
    }

    @Override
    @Transactional(readOnly = true)
    public PersonView getMyPerson(UUID accountId) {
        return provisioner.findPrimary(accountId).map(PersonView::from).orElseThrow(MembershipException::personNotFound);
    }

    @Override
    @Transactional
    public PersonView saveMyPerson(UUID accountId, String fullName) {
        String name = PersonProvisioner.normalize(fullName);
        if (name == null) {
            throw MembershipException.personNameRequired();
        }
        Person person = provisioner.ensurePrimary(accountId, name);
        if (!person.getFullName().equals(name)) {
            person.rename(name);
            person = personRepository.save(person);
        }
        return PersonView.from(person);
    }

    @Override
    @Transactional
    public PersonView saveMyAddress(UUID accountId, AddressInput input) {
        Person person = provisioner.findPrimary(accountId).orElseThrow(MembershipException::personNotFound);
        if (input == null) {
            person.changeAddress(null, null, null, null, null);
        } else {
            String line = PersonProvisioner.normalize(input.addressLine());
            String locality = PersonProvisioner.normalize(input.locality());
            String city = PersonProvisioner.normalize(input.city());
            String pincode = PersonProvisioner.normalize(input.pincode());
            String landmark = PersonProvisioner.normalize(input.landmark());
            if (line == null || locality == null || city == null) {
                throw MembershipException.invalidAddress("Enter the street address, area and city.");
            }
            if (pincode != null && !pincode.matches("^[1-9][0-9]{5}$")) {
                throw MembershipException.invalidAddress("Enter a valid 6-digit pincode.");
            }
            if (line.length() > 255 || locality.length() > 120 || city.length() > 100
                    || (landmark != null && landmark.length() > 150)) {
                throw MembershipException.invalidAddress("One of the address fields is too long.");
            }
            person.changeAddress(line, locality, city, pincode, landmark);
        }
        return PersonView.from(personRepository.save(person));
    }
}
