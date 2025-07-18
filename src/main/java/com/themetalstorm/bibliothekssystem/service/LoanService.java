package com.themetalstorm.bibliothekssystem.service;

import com.themetalstorm.bibliothekssystem.dto.LoanDTO;
import com.themetalstorm.bibliothekssystem.model.*;
import com.themetalstorm.bibliothekssystem.repository.BookRepository;
import com.themetalstorm.bibliothekssystem.repository.LoanRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.themetalstorm.bibliothekssystem.exceptions.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class LoanService {

    private final LoanRepository loanRepository;
    private final JWTService jwtService;
    private final MyUserService myUserService;
    private final BookRepository bookRepository;
    private final BookService bookService;

    public LoanService(LoanRepository loanRepository, JWTService jwtService, MyUserService myUserService, BookRepository bookRepository, BookService bookService) {
        this.loanRepository = loanRepository;
        this.jwtService = jwtService;
        this.myUserService = myUserService;
        this.bookRepository = bookRepository;
        this.bookService = bookService;
    }

    @Transactional
    public LoanDTO addLoan(Integer bookId, String token) {
        String jwtToken = token.substring(7);

        String username = jwtService.extractUserName(jwtToken);
        User user = myUserService.loadWholeUserByUsername(username);
        int userId = user.getId();

        List<Loan> loansByUserId = loanRepository.findLoansByUserId(userId);
        boolean alreadyLoaned = loansByUserId.stream().filter(loan -> loan.getStatus() == LoanStatus.LOANED).anyMatch(loan -> Objects.equals(loan.getBookId(), bookId));
        if (alreadyLoaned) {
            throw new ResourceNotFoundException("User already has a copy of this book");
        }

        Book bookById = bookService.getWholeBookById(bookId);

        if (bookById.getAvailableCopies() <= 0) {
            throw new ResourceNotFoundException("No copies of Book " + bookById.getName() + " available");
        }

        Loan saved = loanRepository.save(new Loan(bookId, userId, LoanStatus.LOANED));
        bookById.setAvailableCopies(bookById.getAvailableCopies() - 1);
        bookRepository.saveAndFlush(bookById);

        return new LoanDTO(saved);
    }

    @Transactional
    public LoanDTO returnLoan(Integer loanId, String token) {
        String jwtToken = token.substring(7);

        String username = jwtService.extractUserName(jwtToken);
        User user = myUserService.loadWholeUserByUsername(username);
        int userId = user.getId();


        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan does not exist in our system"));

        if (loan.getStatus() == LoanStatus.RETURNED) {
            throw new ResourceNotFoundException("Loan has already been returned");
        }

        if (loan.getUserId() != userId) {
            throw new ResourceNotFoundException("You are not allowed to delete this loan");
        }
        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturned(LocalDateTime.now());

        Book bookById = bookService.getWholeBookById(loan.getBookId());
        bookById.setAvailableCopies(bookById.getAvailableCopies() + 1);

        bookRepository.saveAndFlush(bookById);
        Loan toReturn = loanRepository.saveAndFlush(loan);
        return new LoanDTO(toReturn);
    }

    public Page<LoanDTO> getUserLoans(String token, Integer page, Integer size, String sortField, String sortDirection) {
        String jwtToken = token.substring(7);

        String username = jwtService.extractUserName(jwtToken);
        User user = myUserService.loadWholeUserByUsername(username);
        int userId = user.getId();

        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortField);

        Page<Loan> all;
        if (page == null || size == null) {
            all = loanRepository.findLoansByUserId(userId, Pageable.unpaged(sort));
        } else {
            Pageable pageable = PageRequest.of(page, size, sort);
            all = loanRepository.findLoansByUserId(userId, pageable);

        }

        return all.map(LoanDTO::new);
    }

    public Page<LoanDTO> getAllLoans(Integer page, Integer size, String sortField, String sortDirection, Integer userId, Integer bookId, LoanStatus loanStatus) {

        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortField);
        Page<Loan> all;

        if (page == null || size == null) {

            all = loanRepository.findBySearch(page, size, sortField, sortDirection, userId,bookId, loanStatus, Pageable.unpaged(sort));
        } else {
            Pageable pageable = PageRequest.of(page, size, sort);

            all = loanRepository.findBySearch(page, size, sortField, sortDirection, userId, bookId, loanStatus, pageable);
        }

        return all.map(LoanDTO::new);
    }

    @Transactional
    public LoanDTO addLoanAdmin(Integer userId, Integer bookId) {

        if(!myUserService.existsById(userId) ) {
            throw new ResourceNotFoundException("User does not exist");
        }

        List<Loan> loansByUserId = loanRepository.findLoansByUserId(userId);
        boolean alreadyLoaned = loansByUserId.stream().filter(loan -> loan.getStatus() == LoanStatus.LOANED).anyMatch(loan -> Objects.equals(loan.getBookId(), bookId));
        if (alreadyLoaned) {
            throw new ResourceNotFoundException("User already has a copy of this book");
        }

        Book bookById = bookService.getWholeBookById(bookId);

        if (bookById.getAvailableCopies() <= 0) {
            throw new ResourceNotFoundException("No copies of Book " + bookById.getName() + " available");
        }

        Loan saved = loanRepository.save(new Loan(bookId, userId, LoanStatus.LOANED));
        bookById.setAvailableCopies(bookById.getAvailableCopies() - 1);
        bookRepository.saveAndFlush(bookById);

        return new LoanDTO(saved);
    }

    // This is intended for adding historical loan records. As such it skips all checks and doesnt decrement
    // number of available books
    public LoanDTO addLoanAdminBypassChecks(LoanDTO loanDTO) {
        Loan saved = loanRepository.save(new Loan(loanDTO.bookId(), loanDTO.userId(), loanDTO.loanStatus(), loanDTO.returned()));
        return new LoanDTO(loanRepository.save(saved));
    }

    @Transactional
    public LoanDTO editLoan(Integer loanId, LoanDTO loanDTO) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan does not exist in our system"));

        loan.setBookId(loanDTO.bookId());
        loan.setUserId(loanDTO.userId());
        loan.setStatus(loanDTO.loanStatus());
        loan.setReturned(loanDTO.returned());
        return new LoanDTO(loanRepository.saveAndFlush(loan));
    }

    @Transactional
    public LoanDTO returnLoanAdmin(Integer loanId) {

        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan does not exist in our system"));

        if (loan.getStatus() == LoanStatus.RETURNED) {
            throw new ResourceNotFoundException("Loan has already been returned");
        }

        loan.setStatus(LoanStatus.RETURNED);
        loan.setReturned(LocalDateTime.now());

        Book bookById = bookService.getWholeBookById(loan.getBookId());
        bookById.setAvailableCopies(bookById.getAvailableCopies() + 1);

        bookRepository.saveAndFlush(bookById);
        Loan toReturn = loanRepository.saveAndFlush(loan);
        return new LoanDTO(toReturn);

    }

    @Transactional
    public void deleteLoan(Integer loanId) {

        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan does not exist in our system"));

        if (loan.getStatus() != LoanStatus.RETURNED) {
            Book book = bookService.getWholeBookById(loan.getBookId());
            book.setAvailableCopies(book.getAvailableCopies() + 1);
            bookRepository.save(book);
        }

        loanRepository.delete(loan);
    }
}
