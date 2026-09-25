package com.theninjadev.ajoapi.payout;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PayoutMapper {

    @Mapping(target = "id", source = "payout.id")
    @Mapping(target = "beneficiary", source = "beneficiary")
    @Mapping(target = "shortfallKobo", source = "shortfallKobo")
    PayoutSummary toPayoutSummary(Payout payout, UserSummary beneficiary, long shortfallKobo);

    @Mapping(target = "id", source = "claim.id")
    @Mapping(target = "participant", source = "participant")
    @Mapping(target = "outstandingKobo", expression = "java(claim.getAmountKobo() - claim.getSettledAmountKobo())")
    ShortfallClaimSummary toShortfallClaimSummary(ShortfallClaim claim, UserSummary participant);
}
