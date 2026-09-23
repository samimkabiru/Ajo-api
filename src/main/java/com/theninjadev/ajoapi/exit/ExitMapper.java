package com.theninjadev.ajoapi.exit;

import com.theninjadev.ajoapi.auth.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ExitMapper {

    @Mapping(target = "id", source = "exit.id")
    @Mapping(target = "participant", source = "participant")
    ExitRequestSummary toExitRequestSummary(ExitRequest exit, UserSummary participant);

    @Mapping(target = "id", source = "repayment.id")
    @Mapping(target = "participant", source = "participant")
    RepaymentSummary toRepaymentSummary(Repayment repayment, UserSummary participant);
}
