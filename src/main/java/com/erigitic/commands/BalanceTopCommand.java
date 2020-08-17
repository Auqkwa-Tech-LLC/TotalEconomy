/*
 * This file is part of Total Economy, licensed under the MIT License (MIT).
 *
 * Copyright (c) Eric Grandt <https://www.ericgrandt.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.erigitic.commands;

import com.erigitic.config.TEAccount;
import com.erigitic.main.TotalEconomy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import ninja.leaping.configurate.ConfigurationNode;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.economy.Currency;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;

public class BalanceTopCommand implements CommandExecutor {

    private static DecimalFormat formatter = new DecimalFormat("#,###.00");

    public static CommandSpec commandSpec() {
        return CommandSpec.builder()
                .description(Text.of("Display top balances"))
                .permission("totaleconomy.command.balancetop")
                .arguments(
                        GenericArguments.optional(GenericArguments.integer(Text.of("page"))),
                        GenericArguments.optional(GenericArguments.string(Text.of("currency")))
                )
                .executor(new BalanceTopCommand())
                .build();
    }

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) {
        Optional<String> optCurrency = args.<String>getOne("currency");
        Currency currency = null;
        List<Text> accountBalances = new ArrayList<>();

        if (optCurrency.isPresent()) {
            currency = TotalEconomy.getTotalEconomy().getTECurrencyRegistryModule().getById("totaleconomy:" + optCurrency.get().toLowerCase()).orElse(null);
        }

        if (currency == null) {
            currency = TotalEconomy.getTotalEconomy().getDefaultCurrency();
        }

        final int rowsPerPage = 5;
        final Currency fCurrency = currency;
        int offset = 0;
        int pageNum = 0;

        Optional<Integer> opPageNum = args.getOne(Text.of("page"));
        if (opPageNum.isPresent()) {
            pageNum = Math.max(0, opPageNum.get() - 1);
            offset = pageNum * rowsPerPage;
        }

        // the changes here aren't very neat, but it'll do for now
        int fOffset = offset;
        int cmdPageNum = pageNum + 1;
        Task.builder().execute(() -> {
            if (TotalEconomy.getTotalEconomy().isDatabaseEnabled()) {
                try (
                     Connection connection = TotalEconomy.getTotalEconomy().getSqlManager().dataSource.getConnection();
                     Statement statement = connection.createStatement()
                ) {
                    String currencyColumn = fCurrency.getName() + "_balance";
                    statement.execute("SELECT * FROM accounts ORDER BY `" + currencyColumn + "` DESC LIMIT " + fOffset + ", " + rowsPerPage);

                    AtomicInteger position = new AtomicInteger(fOffset + 1);
                    try (ResultSet set = statement.getResultSet()) {
                        while (set.next()) {
                            BigDecimal amount = set.getBigDecimal(currencyColumn);
                            UUID uuid = UUID.fromString(set.getString("uid"));
                            Optional<User> optUser = Sponge.getServiceManager().provideUnchecked(UserStorageService.class).get(uuid);
                            String username = optUser.map(User::getName).orElse("unknown");

                            accountBalances.add(Text.of(TextColors.WHITE, position.getAndIncrement() + ". ", TextColors.GRAY, username, ": ", TextColors.GOLD, fCurrency.getSymbol(), formatter.format(amount)));
                        }
                    }
                } catch (SQLException e) {
                    System.out.println("Failed to query db for ranking.");
                    e.printStackTrace();
                }
            } else {
                ConfigurationNode accountNode = TotalEconomy.getTotalEconomy().getAccountManager().getAccountConfig();
                Map<String, BigDecimal> accountBalancesMap = new HashMap<>();

                accountNode.getChildrenMap().keySet().forEach(accountUUID -> {
                    UUID uuid;

                    // Check if the account is virtual or not. If virtual, skip the rest of the execution and move on to next account.
                    try {
                        uuid = UUID.fromString(accountUUID.toString());
                    } catch (IllegalArgumentException e) {
                        return;
                    }

                    TEAccount playerAccount = (TEAccount) TotalEconomy.getTotalEconomy().getAccountManager().getOrCreateAccount(uuid).get();
                    Text playerName = playerAccount.getDisplayName();

                    accountBalancesMap.put(playerName.toPlain(), playerAccount.getBalance(fCurrency));
                });

                AtomicInteger position = new AtomicInteger(fOffset + 1);
                accountBalancesMap.entrySet().stream()
                        .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                        .limit(rowsPerPage)
                        .forEach(entry ->
                            accountBalances.add(Text.of(TextColors.WHITE, position.getAndIncrement() + ". ", TextColors.GRAY, entry.getKey(), ": ", TextColors.GOLD, fCurrency.getSymbol(), formatter.format(entry.getValue())))
                );
            }

            Text.Builder backBuilder = Text.builder();
            if (cmdPageNum > 1) {
                backBuilder = backBuilder.append(TextSerializers.FORMATTING_CODE.deserialize("&6 \u00AB "))
                            .onHover(TextActions.showText(TextSerializers.FORMATTING_CODE.deserialize("&a&lClick here to go to the last page")))
                            .onClick(TextActions.runCommand("/baltop " + (cmdPageNum - 1) + " " + fCurrency.getName().replace(' ', '_')));
            }
            else {
                backBuilder = backBuilder.append(TextSerializers.FORMATTING_CODE.deserialize("&7 \u00AB "));
            }

            Text.Builder fwrdBuilder = Text.builder();
            if (accountBalances.size() == rowsPerPage) {
                fwrdBuilder = fwrdBuilder.append(TextSerializers.FORMATTING_CODE.deserialize("&6 \u00BB "))
                            .onHover(TextActions.showText(TextSerializers.FORMATTING_CODE.deserialize("&a&lClick here to go to the next page")))
                            .onClick(TextActions.runCommand("/baltop " + (cmdPageNum + 1) + " " + fCurrency.getName().replace(' ', '_')));
            }
            else {
                fwrdBuilder = fwrdBuilder.append(TextSerializers.FORMATTING_CODE.deserialize("&7 \u00BB "));
            }

            Text backArrow = backBuilder.build();
            Text fwrdArrow = fwrdBuilder.build();

            Text headerSeparator = TextSerializers.FORMATTING_CODE.deserialize("&7====================");
            Text title = TextSerializers.FORMATTING_CODE.deserialize(" &6Top Balances ");
            Text footerSeparator = TextSerializers.FORMATTING_CODE.deserialize("&7========================");
            Text header = Text.builder().append(headerSeparator).append(title).append(headerSeparator).build();
            Text footer = Text.builder().append(footerSeparator).append(backArrow).append(fwrdArrow).append(footerSeparator).build();

            Text.Builder messageBuilder = Text.builder().append(Text.of("\n")).append(header).append(Text.of("\n"));
            accountBalances.forEach(entry -> messageBuilder.append(entry).append(Text.of("\n")));
            messageBuilder.append(footer);

            src.sendMessage(messageBuilder.build());
        }).async().submit(TotalEconomy.getTotalEconomy());

        return CommandResult.success();
    }
}
